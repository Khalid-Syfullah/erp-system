package com.erp.sales;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.inventory.application.InventoryInvariantCheck;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Deliveries (SAL-4) and sales returns (SAL-7): drafts, posting through Inventory, the quantity
 * limits and invalid transitions.
 */
class DeliveryAndReturnIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    InventoryInvariantCheck invariants;

    private O2C o;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
        sales.stock(o, "20", "10");
    }

    @AfterEach
    void inventoryStaysConsistent() {
        assertThat(invariants.check(o.inv().company()).clean()).isTrue();
    }

    @Test
    void draftDeliveriesAreEditedCancelledAndDeleted() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "6", null));
        UUID line = sales.orderLines(o, order).getFirst();
        UUID delivery = id(sales.createDelivery(o, order, List.of(sales.deliveryLine(line, "2"))), 201);
        mvc.perform(unsafe(patch(o.path("/deliveries/" + delivery)))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(OrgFixtures.map(
                                "carrier",
                                "DHL",
                                "trackingNumber",
                                "JD0001",
                                "lines",
                                List.of(sales.deliveryLine(line, "3"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier").value("DHL"))
                .andExpect(jsonPath("$.lines[0].quantityBase").value("3.000000"))
                .andExpect(jsonPath("$.shippingAddress").isMap());
        mvc.perform(unsafe(delete(o.path("/deliveries/" + delivery)))
                        .cookie(o.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isNoContent());

        UUID cancelled = id(sales.createDelivery(o, order, null), 201);
        expect(sales.action(o, o.session(), "/deliveries/" + cancelled + "/cancel", 0, null, null), 200);
        expect(sales.action(o, o.session(), "/deliveries/" + cancelled + "/post", 1, "post-" + cancelled, null), 409);

        UUID posted = sales.postedDelivery(o, order, List.of(sales.deliveryLine(line, "1")));
        // A posted delivery is corrected by a return, never edited.
        mvc.perform(unsafe(patch(o.path("/deliveries/" + posted)))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("carrier", "UPS")))
                .andExpect(status().isConflict());
        mvc.perform(unsafe(delete(o.path("/deliveries/" + posted)))
                        .cookie(o.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isConflict());
        expect(sales.action(o, o.session(), "/deliveries/" + posted + "/cancel", 1, null, null), 409);
        expect(sales.action(o, o.session(), "/deliveries/" + posted + "/post", 1, "again-" + posted, null), 409);
        assertThat(sales.onHand(o)).isEqualByComparingTo("19");
        mvc.perform(get(o.path("/deliveries")).cookie(o.session()).param("filter[salesOrderId]", order.toString()))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void onlyStockableLinesAreDelivered() throws Exception {
        UUID service = sales.service(o, "INSTALL");
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "1", null), sales.line(service, "1", "80"));
        List<UUID> lines = sales.orderLines(o, order);
        sales.createDelivery(o, order, List.of(sales.deliveryLine(lines.get(1), "1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_STOCKABLE"));
        sales.createDelivery(
                        o,
                        order,
                        List.of(sales.deliveryLine(lines.getFirst(), "1"), sales.deliveryLine(lines.getFirst(), "1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_LINE"));
        UUID delivery = sales.postedDelivery(o, order, null);
        assertThat(sales.<List<?>>read(o, "/deliveries/" + delivery, "$.lines")).hasSize(1);
        assertThat(sales.<String>read(o, "/sales-orders/" + order, "$.status")).isEqualTo("DELIVERED");
        sales.createDelivery(o, order, null).andExpect(status().isConflict());

        // An order of services only has nothing to deliver.
        UUID services = sales.confirmedOrder(o, sales.line(service, "2", "80"));
        sales.createDelivery(o, services, null).andExpect(status().isConflict());
    }

    @Test
    void returnsNeverExceedWhatWasDelivered() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "5", null));
        UUID line = sales.orderLines(o, order).getFirst();
        UUID draft = id(sales.createDelivery(o, order, List.of(sales.deliveryLine(line, "2"))), 201);
        UUID draftLine = sales.ids(o, "/deliveries/" + draft, "$.lines[*].id").getFirst();
        createReturn(draft, draftLine, "1").andExpect(status().isConflict());

        UUID delivery = sales.postedDelivery(o, order, List.of(sales.deliveryLine(line, "3")));
        UUID deliveryLine =
                sales.ids(o, "/deliveries/" + delivery, "$.lines[*].id").getFirst();
        createReturn(delivery, deliveryLine, "4")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_REMAINING"));
        createReturn(delivery, draftLine, "1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_LINE"));

        UUID first = id(createReturn(delivery, deliveryLine, "2"), 201);
        UUID second = id(createReturn(delivery, deliveryLine, "2"), 201);
        expect(sales.action(o, o.session(), "/sales-returns/" + first + "/receive", 0, "rcv-" + first, null), 200);
        String refused = expect(
                        sales.action(o, o.session(), "/sales-returns/" + second + "/receive", 0, "rcv-" + second, null),
                        422)
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(refused, "$.code")).isEqualTo("QUANTITY_EXCEEDS_REMAINING");
        assertThat(sales.onHand(o)).isEqualByComparingTo("19");

        // Received returns are final; drafts can be cancelled.
        expect(sales.action(o, o.session(), "/sales-returns/" + first + "/receive", 1, "again-" + first, null), 409);
        expect(sales.action(o, o.session(), "/sales-returns/" + first + "/cancel", 1, null, null), 409);
        expect(sales.action(o, o.session(), "/sales-returns/" + second + "/cancel", 0, null, null), 200);
        String order1 = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(order1, "$.lines[0].returnedQuantityBase"))
                .isEqualTo("2.000000");
        assertThat((String) JsonPath.read(order1, "$.lines[0].deliveredQuantityBase"))
                .isEqualTo("3.000000");
        mvc.perform(get(o.path("/sales-returns")).cookie(o.session()).param("filter[status]", "RECEIVED"))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void postingNeedsTheRightPermissionAndAKey() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "1", null));
        UUID delivery = id(sales.createDelivery(o, order, null), 201);
        mvc.perform(unsafe(post(o.path("/deliveries/" + delivery + "/post")))
                        .cookie(o.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().is4xxClientError());
        expect(sales.action(o, o.manager(), "/deliveries/" + delivery + "/post", 0, "mgr-" + delivery, null), 403);
        assertThat(sales.onHand(o)).isEqualByComparingTo("20");
    }

    private ResultActions createReturn(UUID delivery, UUID deliveryLine, String quantity) throws Exception {
        Map<String, Object> body = OrgFixtures.map(
                "deliveryId",
                delivery,
                "reason",
                "Wrong size",
                "lines",
                List.of(OrgFixtures.map("deliveryLineId", deliveryLine, "quantity", quantity)));
        return mvc.perform(unsafe(post(o.path("/sales-returns")))
                .cookie(o.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body)));
    }
}
