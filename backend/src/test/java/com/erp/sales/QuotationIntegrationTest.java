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

import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Quotations (PRODUCT_SPEC.md §9.2): drafts, sending, the customer's answer, cancellation. */
class QuotationIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    private O2C o;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
    }

    @Test
    void draftsDefaultTheirValidityAndAreEdited() throws Exception {
        UUID quotation = id(sales.create(o, "/quotations", sales.order(o, sales.line(o.variant(), "2", null))), 201);
        String created = sales.body(o, "/quotations/" + quotation);
        String date = com.jayway.jsonpath.JsonPath.read(created, "$.quotationDate");
        assertThat(sales.<String>read(o, "/quotations/" + quotation, "$.validUntil"))
                .isEqualTo(java.time.LocalDate.parse(date).plusDays(30).toString());

        mvc.perform(unsafe(patch(o.path("/quotations/" + quotation)))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(OrgFixtures.map(
                                "notes", "Valid for this week", "lines", List.of(sales.line(o.variant(), "5", null))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("137.5000"))
                .andExpect(jsonPath("$.notes").value("Valid for this week"));
        mvc.perform(unsafe(patch(o.path("/quotations/" + quotation)))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("validUntil", "2000-01-01")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("BEFORE_QUOTATION_DATE"));
        mvc.perform(unsafe(delete(o.path("/quotations/" + quotation)))
                        .cookie(o.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isNoContent());
        mvc.perform(get(o.path("/quotations/" + quotation)).cookie(o.session())).andExpect(status().isNotFound());
    }

    @Test
    void theCustomerAnswerEndsTheQuotation() throws Exception {
        UUID rejected = sent();
        mvc.perform(unsafe(post(o.path("/quotations/" + rejected + "/reject")))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("reason", "Too expensive")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Too expensive"));
        expect(sales.action(o, o.session(), "/quotations/" + rejected + "/accept", 2, null, null), 409);
        expect(sales.action(o, o.session(), "/quotations/" + rejected + "/cancel", 2, null, null), 409);

        UUID cancelled = sent();
        expect(sales.action(o, o.session(), "/quotations/" + cancelled + "/cancel", 1, null, null), 200);
        expect(sales.action(o, o.session(), "/quotations/" + cancelled + "/send", 2, null, null), 409);
    }

    @Test
    void invalidTransitionsAreRejected() throws Exception {
        UUID draft = id(sales.create(o, "/quotations", sales.order(o, sales.line(o.variant(), "1", null))), 201);
        // A draft is neither accepted nor rejected.
        expect(sales.action(o, o.session(), "/quotations/" + draft + "/accept", 0, null, null), 409);
        mvc.perform(unsafe(post(o.path("/quotations/" + draft + "/reject")))
                        .cookie(o.session())
                        .header("If-Match", etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("reason", "No")))
                .andExpect(status().isConflict());
        expect(sales.action(o, o.session(), "/quotations/" + draft + "/send", 0, null, null), 200);
        // A sent quotation is frozen.
        expect(sales.action(o, o.session(), "/quotations/" + draft + "/send", 1, null, null), 409);
        mvc.perform(unsafe(patch(o.path("/quotations/" + draft)))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .contentType("application/merge-patch+json")
                        .content(OrgFixtures.json("notes", "late")))
                .andExpect(status().isConflict());
        mvc.perform(unsafe(delete(o.path("/quotations/" + draft)))
                        .cookie(o.session())
                        .header("If-Match", etag(1)))
                .andExpect(status().isConflict());
        // Accepted once: the order exists, a second acceptance is refused.
        expect(sales.action(o, o.session(), "/quotations/" + draft + "/accept", 1, null, null), 201);
        expect(sales.action(o, o.session(), "/quotations/" + draft + "/accept", 2, null, null), 409);
        mvc.perform(get(o.path("/sales-orders")).cookie(o.session()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void anOrderFromAQuotationKeepsTheQuotedPrices() throws Exception {
        // A manager quotes a special price; the seller may still accept it into an order.
        Map<String, Object> body = sales.order(o, sales.line(o.variant(), "2", "19.5"));
        UUID quotation = id(sales.create(o, o.manager(), "/quotations", body), 201);
        expect(sales.action(o, o.session(), "/quotations/" + quotation + "/send", 0, null, null), 200);
        mvc.perform(unsafe(post(o.path("/quotations/" + quotation + "/accept")))
                        .cookie(o.session())
                        .header("If-Match", etag(1))
                        .header("Idempotency-Key", "accept-" + quotation))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("19.500000"))
                .andExpect(jsonPath("$.quotationId").value(quotation.toString()));
        // The order of an accepted quotation is cancelled, never deleted.
        UUID order = UUID.fromString(sales.read(o, "/quotations/" + quotation, "$.salesOrderId"));
        mvc.perform(unsafe(delete(o.path("/sales-orders/" + order)))
                        .cookie(o.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isConflict());
    }

    @Test
    void readingNeedsThePermissionAndOtherCompaniesSeeNothing() throws Exception {
        UUID quotation = id(sales.create(o, "/quotations", sales.order(o, sales.line(o.variant(), "1", null))), 201);
        O2C other = sales.setup();
        mvc.perform(get(o.path("/quotations/" + quotation)).cookie(other.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(other.path("/quotations/" + quotation)).cookie(other.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(o.path("/quotations")).cookie(o.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private UUID sent() throws Exception {
        UUID id = id(sales.create(o, "/quotations", sales.order(o, sales.line(o.variant(), "1", null))), 201);
        expect(sales.action(o, o.session(), "/quotations/" + id + "/send", 0, null, null), 200);
        return id;
    }
}
