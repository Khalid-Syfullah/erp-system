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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.inventory.api.InventoryFacade;
import com.erp.sales.events.SalesOrderCancelled;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Sales orders: pricing approvals (SAL-1), the credit check and its override (SAL-2), reservations
 * (SAL-3), drafts, cancellation and closing, and invalid status transitions.
 */
@RecordApplicationEvents
class SalesOrderIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    InventoryFacade inventory;

    @Autowired
    ApplicationEvents events;

    private O2C o;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
        sales.stock(o, "50", "10");
    }

    // ------------------------------------------------------------------------------ credit check

    @Test
    void creditLimitWarnsBlocksAndCanBeOverridden() throws Exception {
        UUID customer = sales.customer(o.inv(), "LIMITED", "USD", o.terms(), o.taxCode(), "100");
        UUID first = id(sales.create(o, "/sales-orders", order(customer, sales.line(o.variant(), "4", null))), 201);
        expect(confirm(o.session(), first, 0, null), 200);
        assertThat(sales.<String>read(o, "/sales-orders/" + first, "$.creditCheckResult"))
                .isEqualTo("WARNED");

        settings("BLOCK", null);
        UUID second = id(sales.create(o, "/sales-orders", order(customer, sales.line(o.variant(), "1", null))), 201);
        String check = sales.body(o, "/sales-orders/" + second + "/credit-check");
        assertThat((String) JsonPath.read(check, "$.outcome")).isEqualTo("BLOCKED_LIMIT");
        assertThat((String) JsonPath.read(check, "$.openOrdersBase")).isEqualTo("110.00");
        assertThat((String) JsonPath.read(check, "$.orderTotalBase")).isEqualTo("27.50");

        String refused =
                expect(confirm(o.session(), second, 0, null), 422).getResponse().getContentAsString();
        assertThat((String) JsonPath.read(refused, "$.code")).isEqualTo("CREDIT_LIMIT_EXCEEDED");
        assertThat((String) JsonPath.read(refused, "$.errors[0].meta.exposureBase"))
                .isEqualTo("110.00");
        expect(confirm(o.session(), second, 0, "Paid in advance"), 403);
        expect(confirm(o.manager(), second, 0, "Paid in advance"), 200);
        String overridden = sales.body(o, "/sales-orders/" + second);
        assertThat((String) JsonPath.read(overridden, "$.creditCheckResult")).isEqualTo("OVERRIDDEN");
        assertThat((String) JsonPath.read(overridden, "$.creditOverrideReason")).isEqualTo("Paid in advance");
        assertThat((String) JsonPath.read(overridden, "$.creditOverrideBy"))
                .isEqualTo(o.managerUser().id().toString());
    }

    @Test
    void customersOnHoldOrBlockedAreStopped() throws Exception {
        UUID onHold = sales.customer(o.inv(), "HOLD", "USD", o.terms(), null, null);
        mvc.perform(unsafe(put(o.path("/partners/" + onHold + "/customer-profile")))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("currencyCode", "USD", "isOnHold", true)))
                .andExpect(status().isOk());
        UUID order = id(sales.create(o, "/sales-orders", order(onHold, sales.line(o.variant(), "1", null))), 201);
        expect(confirm(o.session(), order, 0, null), 422)
                .getResponse()
                .getContentAsString()
                .contains("PARTNER_ON_HOLD");
        expect(confirm(o.manager(), order, 0, "Cleared by finance"), 200);

        UUID blocked = sales.customer(o.inv(), "BLOCKED", "USD", o.terms(), null, null);
        UUID draft = id(sales.create(o, "/sales-orders", order(blocked, sales.line(o.variant(), "1", null))), 201);
        mvc.perform(unsafe(post(o.path("/partners/" + blocked + "/block")))
                        .cookie(o.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk());
        mvc.perform(unsafe(post(o.path("/sales-orders/" + draft + "/confirm")))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .header("Idempotency-Key", "confirm-blocked-" + draft))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PARTNER_BLOCKED"));
        sales.create(o, "/sales-orders", order(blocked, sales.line(o.variant(), "1", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PARTNER_BLOCKED"));
    }

    // ----------------------------------------------------------------------------------- pricing

    @Test
    void pricesComeFromTheListAndOverridesNeedPermissions() throws Exception {
        // A manual price off the list needs override_price.
        sales.create(o, "/sales-orders", sales.order(o, sales.line(o.variant(), "1", "20")))
                .andExpect(status().isForbidden());
        UUID managed = id(
                sales.create(o, o.manager(), "/sales-orders", sales.order(o, sales.line(o.variant(), "1", "20"))), 201);
        // The seller may edit the order without touching the approved line.
        mvc.perform(unsafe(patch(o.path("/sales-orders/" + managed)))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("notes", "Rush")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value("20.000000"));

        // The list price itself is always fine; a product without a list price needs one entered.
        sales.create(o, "/sales-orders", sales.order(o, sales.line(o.variant(), "1", "25")))
                .andExpect(status().isCreated());
        UUID unlisted = inv.variant(o.inv(), "GADGET", "EA");
        sales.create(o, "/sales-orders", sales.order(o, sales.line(unlisted, "1", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRICE_MISSING"));
        sales.create(o, "/sales-orders", sales.order(o, sales.line(unlisted, "1", "12.5")))
                .andExpect(status().isCreated());

        // Quantity tiers: 10 or more cost 22.
        mvc.perform(unsafe(post(o.path("/price-lists/" + o.priceList() + "/items")))
                        .cookie(o.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "variantId",
                                o.variant(),
                                "uomId",
                                inv.uom("EA"),
                                "minQuantity",
                                "10",
                                "unitPrice",
                                "22")))
                .andExpect(status().isCreated());
        sales.create(o, "/sales-orders", sales.order(o, sales.line(o.variant(), "12", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].unitPrice").value("22.000000"))
                .andExpect(jsonPath("$.subtotal").value("264.0000"));

        // Discounts above the company threshold need discount_high.
        settings("WARN", "10");
        Map<String, Object> discounted = sales.line(o.variant(), "1", null);
        discounted.put("discountPercent", "15");
        sales.create(o, "/sales-orders", sales.order(o, discounted)).andExpect(status().isForbidden());
        sales.create(o, o.manager(), "/sales-orders", sales.order(o, discounted))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value("21.2500"));
        discounted.put("discountPercent", "10");
        sales.create(o, "/sales-orders", sales.order(o, discounted)).andExpect(status().isCreated());
    }

    @Test
    void theQuoteEndpointPricesWithoutStoring() throws Exception {
        mvc.perform(unsafe(post(o.path("/pricing/quote")))
                        .cookie(o.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(OrgFixtures.map(
                                "customerId",
                                o.customer(),
                                "lines",
                                List.of(OrgFixtures.map(
                                        "variantId", o.variant(), "quantity", "3", "uomId", inv.uom("EA")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceListId").value(o.priceList().toString()))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("25.000000"))
                .andExpect(jsonPath("$.lines[0].taxCodeId").value(o.taxCode().toString()))
                .andExpect(jsonPath("$.total").value("82.50"));
        mvc.perform(get(o.path("/sales-orders")).cookie(o.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ----------------------------------------------------------------------- drafts and states

    @Test
    void draftsAreEditedRepricedAndDeleted() throws Exception {
        UUID order = sales.draftOrder(o, sales.line(o.variant(), "2", null));
        mvc.perform(unsafe(patch(o.path("/sales-orders/" + order)))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(OrgFixtures.map(
                                "customerReference", "PO-778", "lines", List.of(sales.line(o.variant(), "3", null))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("82.5000"))
                .andExpect(jsonPath("$.customerReference").value("PO-778"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(unsafe(patch(o.path("/sales-orders/" + order)))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("notes", "stale")))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(unsafe(patch(o.path("/sales-orders/" + order)))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("invoicePolicy", "LATER")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(delete(o.path("/sales-orders/" + order)))
                        .cookie(o.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isNoContent());
        mvc.perform(get(o.path("/sales-orders/" + order)).cookie(o.session())).andExpect(status().isNotFound());
    }

    @Test
    void invalidStatusTransitionsAreRejected() throws Exception {
        UUID draft = sales.draftOrder(o, sales.line(o.variant(), "2", null));
        // A draft cannot be delivered, reserved, closed or invoiced.
        sales.createDelivery(o, draft, null).andExpect(status().isConflict());
        expect(sales.action(o, o.session(), "/sales-orders/" + draft + "/reserve", 0, null, null), 409);
        expect(sales.action(o, o.session(), "/sales-orders/" + draft + "/close", 0, null, null), 409);
        sales.create(o, "/invoices/from-order", OrgFixtures.map("salesOrderId", draft))
                .andExpect(status().isConflict());

        expect(confirm(o.session(), draft, 0, null), 200);
        // Confirmed: not again, not edited, not deleted; closing needs a delivery first.
        expect(confirm(o.session(), draft, 1, null), 409);
        mvc.perform(unsafe(patch(o.path("/sales-orders/" + draft)))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("notes", "late")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(unsafe(delete(o.path("/sales-orders/" + draft)))
                        .cookie(o.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isConflict());
        expect(sales.action(o, o.session(), "/sales-orders/" + draft + "/close", 1, null, null), 409);

        // Delivered goods: no cancel any more.
        sales.postedDelivery(o, draft, null);
        int version = sales.version(o, "/sales-orders/" + draft);
        expect(sales.action(o, o.session(), "/sales-orders/" + draft + "/cancel", version, null, null), 409);

        // Cancelled orders allow nothing.
        UUID other = sales.draftOrder(o, sales.line(o.variant(), "1", null));
        expect(sales.action(o, o.session(), "/sales-orders/" + other + "/cancel", 0, null, null), 200);
        expect(confirm(o.session(), other, 1, null), 409);
        expect(sales.action(o, o.session(), "/sales-orders/" + other + "/cancel", 1, null, null), 409);
    }

    @Test
    void cancellingReleasesReservationsAndDrafts() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "4", null));
        assertThat(reserved()).isEqualByComparingTo("4");
        UUID delivery = id(sales.createDelivery(o, order, null), 201);

        mvc.perform(unsafe(post(o.path("/sales-orders/" + order + "/cancel")))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("reason", "Customer withdrew")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Customer withdrew"))
                .andExpect(jsonPath("$.lines[0].reservedQuantityBase").value("0.000000"));
        assertThat(reserved()).isEqualByComparingTo("0");
        assertThat(sales.<String>read(o, "/deliveries/" + delivery, "$.status")).isEqualTo("CANCELLED");
        assertThat(events.stream(SalesOrderCancelled.class))
                .singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("Customer withdrew"));
    }

    @Test
    void closingShortReleasesTheRestAndKeepsInvoicing() throws Exception {
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "10", null));
        UUID line = sales.orderLines(o, order).getFirst();
        sales.postedDelivery(o, order, List.of(sales.deliveryLine(line, "4")));
        assertThat(reserved()).isEqualByComparingTo("6");

        int version = sales.version(o, "/sales-orders/" + order);
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/close", version, null, null), 200);
        assertThat(reserved()).isEqualByComparingTo("0");
        sales.createDelivery(o, order, null).andExpect(status().isConflict());

        UUID invoice = sales.postedInvoice(o, order);
        assertThat(sales.<String>read(o, "/invoices/" + invoice, "$.lines[0].quantityBase"))
                .isEqualTo("4.000000");
        String closed = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(closed, "$.status")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(closed, "$.invoiceStatus")).isEqualTo("INVOICED");
    }

    @Test
    void confirmIsIdempotentAndNeedsAKey() throws Exception {
        UUID order = sales.draftOrder(o, sales.line(o.variant(), "1", null));
        mvc.perform(unsafe(post(o.path("/sales-orders/" + order + "/confirm")))
                        .cookie(o.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().is4xxClientError());
        String first =
                expect(confirm(o.session(), order, 0, null), 200).getResponse().getContentAsString();
        String replay =
                expect(confirm(o.session(), order, 0, null), 200).getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(reserved()).isEqualByComparingTo("1");
    }

    @Test
    void withoutReservationOnConfirmNothingIsReserved() throws Exception {
        mvc.perform(unsafe(put(o.path("/settings/sales")))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "defaultInvoicePolicy",
                                "ORDERED",
                                "creditCheckMode",
                                "NONE",
                                "quotationValidityDays",
                                14,
                                "reserveOnConfirm",
                                false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserveOnConfirm").value(false));
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "3", null));
        String confirmed = sales.body(o, "/sales-orders/" + order);
        assertThat((String) JsonPath.read(confirmed, "$.invoicePolicy")).isEqualTo("ORDERED");
        assertThat((String) JsonPath.read(confirmed, "$.lines[0].reservedQuantityBase"))
                .isEqualTo("0.000000");
        assertThat(reserved()).isEqualByComparingTo("0");
        // Deliveries take free stock without a reservation.
        sales.postedDelivery(o, order, null);
        assertThat(sales.onHand(o)).isEqualByComparingTo("47");
    }

    // ------------------------------------------------------------------------------ helpers

    private Map<String, Object> order(UUID customer, Object... lines) {
        Map<String, Object> body = sales.order(o, lines);
        body.put("customerId", customer);
        return body;
    }

    private ResultActions confirm(Cookie session, UUID order, int version, String overrideReason) throws Exception {
        String body =
                overrideReason == null ? null : OrgFixtures.json("overrideCredit", Map.of("reason", overrideReason));
        return sales.action(
                o,
                session,
                "/sales-orders/" + order + "/confirm",
                version,
                "confirm-" + version + "-" + (overrideReason == null ? "x" : "o") + "-"
                        + session.getValue().hashCode() + "-" + order,
                body);
    }

    private void settings(String creditMode, String discountThreshold) throws Exception {
        String current = mvc.perform(get(o.path("/settings/sales")).cookie(o.session()))
                .andReturn()
                .getResponse()
                .getHeader("ETag");
        mvc.perform(unsafe(put(o.path("/settings/sales")))
                        .cookie(o.session())
                        .header("If-Match", current)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "defaultInvoicePolicy",
                                "DELIVERED",
                                "creditCheckMode",
                                creditMode,
                                "quotationValidityDays",
                                30,
                                "reserveOnConfirm",
                                true,
                                "discountApprovalThresholdPercent",
                                discountThreshold)))
                .andExpect(status().isOk());
    }

    private java.math.BigDecimal reserved() {
        return sales.inCompany(
                o, () -> inventory.availability(o.variant(), o.warehouse()).reserved());
    }
}
