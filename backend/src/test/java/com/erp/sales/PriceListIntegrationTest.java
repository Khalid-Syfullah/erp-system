package com.erp.sales;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Price lists (SAL-1): one default per currency, customer group lists, items and tiers. */
class PriceListIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    InventoryFixtures inv;

    private O2C o;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
    }

    @Test
    void oneDefaultPerCurrency() throws Exception {
        UUID second = id(list("PROMO", "USD", true, null), 201);
        mvc.perform(get(o.path("/price-lists/" + o.priceList())).cookie(o.session()))
                .andExpect(jsonPath("$.isDefault").value(false));
        mvc.perform(get(o.path("/price-lists")).cookie(o.session()).param("filter[isDefault]", "true"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(second.toString()));
        // Making the first one default again moves the flag back.
        mvc.perform(unsafe(patch(o.path("/price-lists/" + o.priceList())))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("isDefault", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(get(o.path("/price-lists/" + second)).cookie(o.session()))
                .andExpect(jsonPath("$.isDefault").value(false));
        list("PROMO", "USD", false, null).andExpect(status().isConflict());
        list("bad code", "USD", false, null).andExpect(status().isUnprocessableContent());
    }

    @Test
    void theCustomerGroupListWinsOverTheDefault() throws Exception {
        UUID group = id(
                mvc.perform(unsafe(post(o.path("/partner-groups")))
                        .cookie(o.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("code", "VIP", "name", "VIP", "appliesTo", "CUSTOMER"))),
                201);
        UUID supplierGroup = id(
                mvc.perform(unsafe(post(o.path("/partner-groups")))
                        .cookie(o.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("code", "VENDORS", "name", "Vendors", "appliesTo", "SUPPLIER"))),
                201);
        list("WRONG", "USD", false, supplierGroup)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/customerGroupId"));
        UUID vip = id(list("VIP", "USD", false, group), 201);
        item(vip, "20").andExpect(status().isCreated());
        mvc.perform(unsafe(put(o.path("/partners/" + o.customer() + "/customer-profile")))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "customerGroupId", group, "currencyCode", "USD", "defaultTaxCodeId", o.taxCode())))
                .andExpect(status().isOk());
        // The group's list decides; it prices only from 10 units on.
        sales.create(o, "/sales-orders", sales.order(o, sales.line(o.variant(), "1", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRICE_MISSING"));
        sales.create(o, "/sales-orders", sales.order(o, sales.line(o.variant(), "10", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceListId").value(vip.toString()))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("20.000000"));

        // An inactive group list falls back to the default.
        mvc.perform(unsafe(patch(o.path("/price-lists/" + vip)))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("isActive", false)))
                .andExpect(status().isOk());
        sales.create(o, "/sales-orders", sales.order(o, sales.line(o.variant(), "1", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceListId").value(o.priceList().toString()));
        // An explicitly named inactive list is refused.
        var body = sales.order(o, sales.line(o.variant(), "1", null));
        body.put("priceListId", vip);
        sales.create(o, "/sales-orders", body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/priceListId"));
    }

    @Test
    void itemsAreManagedAndValidated() throws Exception {
        UUID itemId = id(item(o.priceList(), "30").andExpect(status().isCreated()), 201);
        assertThat(itemId).isNotNull();
        // The same tier twice is a conflict.
        item(o.priceList(), "31").andExpect(status().isConflict());
        mvc.perform(unsafe(patch(o.path("/price-lists/" + o.priceList() + "/items/" + itemId)))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("unitPrice", "28.5", "minQuantity", "5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPrice").value("28.500000"))
                .andExpect(jsonPath("$.minQuantity").value("5.000000"));
        mvc.perform(unsafe(patch(o.path("/price-lists/" + o.priceList() + "/items/" + itemId)))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("unitPrice", "-1")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(o.path("/price-lists/" + o.priceList() + "/items")).cookie(o.session()))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(unsafe(delete(o.path("/price-lists/" + o.priceList() + "/items/" + itemId)))
                        .cookie(o.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isNoContent());
        mvc.perform(get(o.path("/price-lists/" + o.priceList() + "/items/" + itemId))
                        .cookie(o.session()))
                .andExpect(status().isNotFound());
        // Unknown products and units that do not convert are refused.
        mvc.perform(unsafe(post(o.path("/price-lists/" + o.priceList() + "/items")))
                        .cookie(o.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "variantId", UUID.randomUUID(), "uomId", inv.uom("EA"), "unitPrice", "1")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(post(o.path("/price-lists/" + o.priceList() + "/items")))
                        .cookie(o.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("variantId", o.variant(), "uomId", inv.uom("KG"), "unitPrice", "1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("UOM_NOT_CONVERTIBLE"));
    }

    @Test
    void managingNeedsThePermission() throws Exception {
        mvc.perform(unsafe(post(o.path("/price-lists")))
                        .cookie(o.manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("code", "X", "name", "X", "currencyCode", "USD")))
                .andExpect(status().isForbidden());
        mvc.perform(get(o.path("/price-lists")).cookie(o.manager())).andExpect(status().isForbidden());
    }

    private ResultActions list(String code, String currency, boolean isDefault, UUID group) throws Exception {
        return mvc.perform(unsafe(post(o.path("/price-lists")))
                .cookie(o.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.json(
                        "code",
                        code,
                        "name",
                        "List " + code,
                        "currencyCode",
                        currency,
                        "isDefault",
                        isDefault,
                        "customerGroupId",
                        group)));
    }

    private ResultActions item(UUID list, String price) throws Exception {
        return mvc.perform(unsafe(post(o.path("/price-lists/" + list + "/items")))
                .cookie(o.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.json(
                        "variantId", o.variant(), "uomId", inv.uom("EA"), "minQuantity", "10", "unitPrice", price)));
    }
}
