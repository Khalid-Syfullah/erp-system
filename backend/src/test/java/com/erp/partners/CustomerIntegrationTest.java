package com.erp.partners;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.partners.api.PartnersFacade;
import com.erp.support.IntegrationTest;
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

/** Customer profiles (PRODUCT_SPEC.md §5, API.md §17.4): credit limit, hold, defaults, listing. */
class CustomerIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    PartnersFacade partners;

    private O2C o;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
    }

    @Test
    void aPartnerBecomesACustomerWithItsProfile() throws Exception {
        UUID partner = id(
                mvc.perform(unsafe(post(o.path("/partners")))
                        .cookie(o.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("code", "NEWCO", "name", "New Co", "partnerType", "ORGANIZATION"))),
                201);
        mvc.perform(get(o.path("/partners/" + partner)).cookie(o.session()))
                .andExpect(jsonPath("$.isCustomer").value(false));
        // Creating needs If-Match W/"0"; a stale version is refused.
        profile(partner, 1, OrgFixtures.json("currencyCode", "USD")).andExpect(status().isPreconditionFailed());
        profile(partner, 0, OrgFixtures.json("currencyCode", "USD", "creditLimit", "5000", "paymentTermsId", o.terms()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimit").value("5000.0000"))
                .andExpect(jsonPath("$.isOnHold").value(false))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get(o.path("/partners/" + partner)).cookie(o.session()))
                .andExpect(jsonPath("$.isCustomer").value(true))
                .andExpect(jsonPath("$.customerProfile.creditLimit").value("5000.0000"));
        profile(partner, 1, OrgFixtures.json("currencyCode", "USD", "isOnHold", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isOnHold").value(true))
                .andExpect(jsonPath("$.creditLimit").doesNotExist());

        var info = sales.inCompany(o, () -> partners.customer(partner).orElseThrow());
        assertThat(info.onHold()).isTrue();
        assertThat(info.usable()).isTrue();
        assertThat(sales.inCompany(o, () -> partners.customer(UUID.randomUUID())))
                .isEmpty();

        mvc.perform(get(o.path("/customers")).cookie(o.session()).param("filter[isOnHold]", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("NEWCO"));
        mvc.perform(get(o.path("/customers")).cookie(o.session()))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void profilesAreValidated() throws Exception {
        UUID purchaseTax = id(
                mvc.perform(unsafe(post(o.path("/tax-codes")))
                        .cookie(o.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "code", "IN10", "name", "Input", "scope", "PURCHASE", "ratePercent", "10"))),
                201);
        UUID supplierGroup = id(
                mvc.perform(unsafe(post(o.path("/partner-groups")))
                        .cookie(o.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("code", "SUPP", "name", "Suppliers", "appliesTo", "SUPPLIER"))),
                201);
        profile(
                        o.customer(),
                        1,
                        OrgFixtures.json(
                                "currencyCode",
                                "XXX",
                                "defaultTaxCodeId",
                                purchaseTax,
                                "customerGroupId",
                                supplierGroup,
                                "paymentTermsId",
                                UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(4));
        profile(o.customer(), 1, OrgFixtures.json("currencyCode", "USD", "creditLimit", "-1"))
                .andExpect(status().isUnprocessableContent());
        profile(UUID.randomUUID(), 0, OrgFixtures.json("currencyCode", "USD")).andExpect(status().isNotFound());
    }

    @Test
    void managingCustomersNeedsThePermission() throws Exception {
        mvc.perform(unsafe(put(o.path("/partners/" + o.customer() + "/customer-profile")))
                        .cookie(o.manager())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("currencyCode", "USD")))
                .andExpect(status().isForbidden());
        mvc.perform(get(o.path("/customers")).cookie(o.manager())).andExpect(status().isForbidden());
    }

    private ResultActions profile(UUID partner, int version, String body) throws Exception {
        return mvc.perform(unsafe(put(o.path("/partners/" + partner + "/customer-profile")))
                .cookie(o.session())
                .header("If-Match", etag(version))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
