package com.erp.accounting;

import static com.erp.support.AccountingFixtures.amounts;
import static com.erp.support.AccountingFixtures.nonZero;
import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.sales.events.InvoicePosted;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operational documents are booked by Accounting alone, from the events of Inventory, Procurement
 * and Sales (PRODUCT_SPEC.md §8.6, ADR-005): receipts through GRNI, bills with input tax, returns and
 * debit notes; deliveries at cost, invoices with output tax, returns and credit notes. An event is
 * booked once (ACC-5), and a document whose posting fails is rolled back with it.
 */
@RecordApplicationEvents
class OperationalPostingIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    SalesFixtures sales;

    @Autowired
    LedgerInvariantCheck invariants;

    @Autowired
    ApplicationEvents events;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Test
    void procureToPayIsBookedThroughGoodsReceivedNotInvoiced() throws Exception {
        P2P p = proc.setup();
        Books b = acc.books(p.inv().company());
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "5.00", p.taxCode()));
        UUID receipt = proc.postedReceipt(p, order, null);
        assertThat(nonZero(acc.balances(b))).isEqualTo(amounts("1200", "50", "2050", "-50"));

        UUID bill = UUID.fromString(JsonPath.read(
                expect(
                                mvc.perform(unsafe(post(p.path("/supplier-bills/from-receipts")))
                                        .cookie(p.session())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(OrgFixtures.json(
                                                "goodsReceiptIds", List.of(receipt),
                                                "supplierInvoiceNumber", "INV-100",
                                                "billDate", p.inv().today()))),
                                201)
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        expect(proc.action(p, p.session(), "/supplier-bills/" + bill + "/post", 0, "post-" + bill, null), 200);
        assertThat(acc.lines(b, "procurement", "BILL", bill))
                .isEqualTo(amounts("2050", "50", "1300", "5", "2000", "-55"));
        assertThat(acc.<String>read(b, "/payables?filter[partnerId]=" + p.supplier(), "$.data[0].openAmount"))
                .isEqualTo("55.0000");

        // Two units back to the supplier: stock leaves at the receipt value, GRNI waits for the credit.
        UUID receiptLine = proc.receiptLines(p, receipt).getFirst();
        UUID purchaseReturn = id(
                mvc.perform(unsafe(post(p.path("/purchase-returns")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "goodsReceiptId",
                                receipt,
                                "reason",
                                "Damaged",
                                "lines",
                                List.of(OrgFixtures.map("goodsReceiptLineId", receiptLine, "quantity", "2"))))),
                201);
        expect(
                proc.action(
                        p,
                        p.session(),
                        "/purchase-returns/" + purchaseReturn + "/post",
                        0,
                        "ret-" + purchaseReturn,
                        null),
                200);
        assertThat(nonZero(acc.balances(b))).isEqualTo(amounts("1200", "40", "2050", "10", "1300", "5", "2000", "-55"));

        UUID note = id(
                mvc.perform(unsafe(post(p.path("/supplier-bills")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "documentType",
                                "DEBIT_NOTE",
                                "supplierId",
                                p.supplier(),
                                "supplierInvoiceNumber",
                                "CN-7",
                                "billDate",
                                p.inv().today(),
                                "originalBillId",
                                bill,
                                "lines",
                                List.of(OrgFixtures.map(
                                        "goodsReceiptLineId",
                                        receiptLine,
                                        "quantity",
                                        "2",
                                        "unitPrice",
                                        "5.00",
                                        "taxCodeId",
                                        p.taxCode()))))),
                201);
        expect(proc.action(p, p.session(), "/supplier-bills/" + note + "/post", 0, "post-" + note, null), 200);
        assertThat(acc.lines(b, "procurement", "DEBIT_NOTE", note))
                .isEqualTo(amounts("2050", "-10", "1300", "-1", "2000", "11"));
        assertThat(nonZero(acc.balances(b))).isEqualTo(amounts("1200", "40", "1300", "4", "2000", "-44"));
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    @Test
    void orderToCashIsBookedAtCostWithRevenueAndTaxAndEachEventOnce() throws Exception {
        O2C o = sales.setup();
        Books b = acc.books(o.inv().company());
        sales.stock(o, "20", "10");
        assertThat(nonZero(acc.balances(b))).isEqualTo(amounts("1200", "200", "3900", "-200"));

        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "4", null));
        UUID delivery = sales.postedDelivery(o, order, null);
        assertThat(nonZero(acc.balances(b))).isEqualTo(amounts("1200", "160", "3900", "-200", "5000", "40"));

        UUID invoice = sales.postedInvoice(o, order);
        assertThat(acc.lines(b, "sales", "INVOICE", invoice))
                .isEqualTo(amounts("1100", "110", "4000", "-100", "2100", "-10"));
        assertThat(acc.<String>read(b, "/receivables?filter[partnerId]=" + o.customer(), "$.data[0].openAmount"))
                .isEqualTo("110.0000");

        UUID deliveryLine =
                sales.ids(o, "/deliveries/" + delivery, "$.lines[*].id").getFirst();
        UUID salesReturn = id(
                sales.create(
                        o,
                        "/sales-returns",
                        OrgFixtures.map(
                                "deliveryId",
                                delivery,
                                "reason",
                                "Damaged",
                                "lines",
                                List.of(OrgFixtures.map("deliveryLineId", deliveryLine, "quantity", "1")))),
                201);
        expect(
                sales.action(
                        o, o.session(), "/sales-returns/" + salesReturn + "/receive", 0, "rcv-" + salesReturn, null),
                200);
        UUID invoiceLine = sales.ids(o, "/invoices/" + invoice, "$.lines[*].id").getFirst();
        UUID returnLine =
                sales.ids(o, "/sales-returns/" + salesReturn, "$.lines[*].id").getFirst();
        UUID creditNote = id(
                sales.create(
                        o,
                        "/invoices",
                        OrgFixtures.map(
                                "documentType",
                                "CREDIT_NOTE",
                                "customerId",
                                o.customer(),
                                "originalInvoiceId",
                                invoice,
                                "salesReturnId",
                                salesReturn,
                                "lines",
                                List.of(OrgFixtures.map(
                                        "originalInvoiceLineId",
                                        invoiceLine,
                                        "salesReturnLineId",
                                        returnLine,
                                        "quantity",
                                        "1")))),
                201);
        expect(sales.action(o, o.session(), "/invoices/" + creditNote + "/post", 0, "post-" + creditNote, null), 200);
        assertThat(acc.lines(b, "sales", "CREDIT_NOTE", creditNote))
                .isEqualTo(amounts("1100", "-27.5", "4100", "25", "2100", "2.5"));
        assertThat(nonZero(acc.balances(b)))
                .isEqualTo(amounts(
                        "1200", "170", "3900", "-200", "5000", "30", "1100", "82.5", "4000", "-100", "4100", "25",
                        "2100", "-7.5"));

        // The same event again (a redelivery) books nothing more.
        InvoicePosted posted = events.stream(InvoicePosted.class)
                .filter(e -> e.invoiceId().equals(invoice))
                .findFirst()
                .orElseThrow();
        int before = entryCount(b);
        CurrentContext.callWith(
                RequestContext.forRequest("redelivery-" + UUID.randomUUID()).withCompany(b.company()),
                () -> tx.execute(s -> {
                    publisher.publishEvent(posted);
                    return null;
                }));
        assertThat(entryCount(b)).isEqualTo(before);
        assertThat(acc.lines(b, "sales", "INVOICE", invoice))
                .isEqualTo(amounts("1100", "110", "4000", "-100", "2100", "-10"));
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    @Test
    void adjustmentsFollowTheirReasonTransfersBookNothingAndReversalsMirror() throws Exception {
        InventoryFixtures.Setup s = inv.setup();
        Books b = acc.books(s.company());
        inv.opening(s, s.variant(), s.warehouse().stock(), "10", "4");
        // The adjustment reason has its own expense account.
        UUID shrinkage = acc.account(b, "5150", "EXPENSE", "COST_OF_GOODS_SOLD");
        mvc.perform(unsafe(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                b.path("/account-mappings")))
                        .cookie(b.session())
                        .header("If-Match", etagOf(b, "/account-mappings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "mappings",
                                List.of(OrgFixtures.map(
                                        "mappingKey",
                                        "INVENTORY_ADJUSTMENT",
                                        "scopeType",
                                        "REASON_CODE",
                                        "scopeId",
                                        s.adjustmentReason(),
                                        "accountId",
                                        shrinkage)))))
                .andExpect(status().isOk());

        UUID adjustment = inv.postMovement(
                s,
                inv.movement(
                        s,
                        "ADJUSTMENT",
                        s.warehouse().id(),
                        "reasonCodeId",
                        s.adjustmentReason(),
                        "lines",
                        List.of(inv.line(s.variant(), s.warehouse().stock(), null, "2"))));
        assertThat(acc.lines(b, "inventory", "STOCK_MOVEMENT", adjustment))
                .isEqualTo(amounts("5150", "8", "1200", "-8"));

        // A transfer between warehouses on the same inventory account books nothing.
        InventoryFixtures.Warehouse other = inv.warehouse(s, "WH2");
        UUID transfer = inv.postMovement(
                s,
                inv.movement(
                        s,
                        "TRANSFER",
                        s.warehouse().id(),
                        "destWarehouseId",
                        other.id(),
                        "lines",
                        List.of(inv.line(s.variant(), s.warehouse().stock(), other.stock(), "3"))));
        assertThat(acc.lines(b, "inventory", "STOCK_MOVEMENT", transfer)).isEmpty();

        // Reversing the adjustment mirrors its entry.
        String reversal = expect(
                        mvc.perform(unsafe(post(s.path("/stock-movements/" + adjustment + "/reverse")))
                                .cookie(s.session())
                                .header("If-Match", OrgFixtures.etag(1))
                                .header("Idempotency-Key", "reverse-" + adjustment)),
                        201)
                .getResponse()
                .getContentAsString();
        UUID reversalId = UUID.fromString(JsonPath.read(reversal, "$.id"));
        assertThat(acc.lines(b, "inventory", "STOCK_MOVEMENT", reversalId))
                .isEqualTo(amounts("5150", "-8", "1200", "8"));
        assertThat(nonZero(acc.balances(b))).isEqualTo(amounts("1200", "40", "3900", "-40"));
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    /** Transaction rollback: no mapping, no posting, no delivery. */
    @Test
    void aDocumentWhosePostingFailsIsRolledBack() throws Exception {
        O2C o = sales.setup();
        Books b = acc.books(o.inv().company());
        sales.stock(o, "5", "10");
        UUID order = sales.confirmedOrder(o, sales.line(o.variant(), "2", null));
        CurrentContext.callWith(
                RequestContext.forRequest("unmap-" + UUID.randomUUID()).withCompany(b.company()),
                () -> tx.execute(s -> dsl.execute(
                        "DELETE FROM accounting.account_mappings WHERE company_id = ? AND mapping_key = 'COGS'",
                        b.company())));
        UUID delivery = id(sales.createDelivery(o, order, null), 201);
        sales.action(o, o.session(), "/deliveries/" + delivery + "/post", 0, "post-" + delivery, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_MAPPING_MISSING"));
        assertThat(sales.<String>read(o, "/deliveries/" + delivery, "$.status")).isEqualTo("DRAFT");
        assertThat(sales.onHand(o)).isEqualByComparingTo("5");
        assertThat(nonZero(acc.balances(b))).isEqualTo(amounts("1200", "50", "3900", "-50"));

        // Mapped again, the same delivery posts.
        mvc.perform(unsafe(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                b.path("/account-mappings")))
                        .cookie(b.session())
                        .header(
                                "If-Match",
                                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                                        b.path("/account-mappings"))
                                                .cookie(b.session()))
                                        .andReturn()
                                        .getResponse()
                                        .getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "mappings",
                                List.of(OrgFixtures.map(
                                        "mappingKey",
                                        "COGS",
                                        "scopeType",
                                        "DEFAULT",
                                        "accountId",
                                        b.account("5000"))))))
                .andExpect(status().isOk());
        expect(sales.action(o, o.session(), "/deliveries/" + delivery + "/post", 0, "post2-" + delivery, null), 200);
        assertThat(sales.onHand(o)).isEqualByComparingTo("3");
        assertThat(nonZero(acc.balances(b))).isEqualTo(amounts("1200", "30", "3900", "-50", "5000", "20"));
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    // ------------------------------------------------------------------------------ helpers

    private String etagOf(Books b, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(b.path(path))
                        .cookie(b.session()))
                .andReturn()
                .getResponse()
                .getHeader("ETag");
    }

    private int entryCount(Books b) throws Exception {
        return JsonPath.<List<?>>read(acc.body(b, "/journal-entries?limit=200"), "$.data")
                .size();
    }
}
