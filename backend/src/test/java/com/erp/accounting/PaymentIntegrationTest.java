package com.erp.accounting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Customer receipts and supplier payments (PRODUCT_SPEC.md §8.8): a posted payment debits the bank
 * and credits the control account through its own open item; allocations settle invoices and bills
 * fully or partly, the rest stays on account; allocations are undone and payments voided by
 * reversal; foreign-currency settlements book the realized difference.
 */
class PaymentIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    LedgerInvariantCheck invariants;

    @Test
    void aReceiptSettlesInvoicesFullyPartlyAndOnAccountAndIsVoided() throws Exception {
        O2C o = sales.setup();
        Books b = acc.books(o.inv().company());
        UUID service = sales.service(o, "SVC");
        UUID first = invoice(o, o.customer(), service, "4"); // 110
        UUID second = invoice(o, o.customer(), service, "2"); // 55
        UUID firstItem = itemId(b, "receivables", first);
        UUID secondItem = itemId(b, "receivables", second);

        // Over-allocation is refused before anything is stored.
        acc.create(
                        b,
                        b.session(),
                        "/payments",
                        acc.payment(b, "INBOUND", o.customer(), b.bankAccount(), "100", allocations(firstItem, "110")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItem("ALLOCATION_INVALID")));
        acc.create(
                        b,
                        b.session(),
                        "/payments",
                        acc.payment(
                                b, "INBOUND", o.customer(), b.bankAccount(), "200", allocations(firstItem, "110.01")))
                .andExpect(status().isUnprocessableContent());

        UUID payment = acc.postedPayment(
                b,
                acc.payment(
                        b,
                        "INBOUND",
                        o.customer(),
                        b.bankAccount(),
                        "200",
                        allocations(firstItem, "110", secondItem, "30")));
        String posted = acc.body(b, "/payments/" + payment);
        assertThat((String) JsonPath.read(posted, "$.status")).isEqualTo("POSTED");
        assertThat((String) JsonPath.read(posted, "$.number")).startsWith("RCT-");
        assertThat((String) JsonPath.read(posted, "$.unallocatedAmount")).isEqualTo("60.0000");
        assertThat(acc.openItem(b, "receivables", first))
                .containsEntry("status", "SETTLED")
                .containsEntry("openAmount", "0.0000");
        assertThat(acc.openItem(b, "receivables", second))
                .containsEntry("status", "PARTIALLY_SETTLED")
                .containsEntry("openAmount", "25.0000");
        assertThat(sales.<String>read(o, "/invoices/" + first + "/settlement", "$.status"))
                .isEqualTo("SETTLED");
        assertThat(acc.balance(b, "1010")).isEqualByComparingTo("200");
        assertThat(acc.balance(b, "1100")).isEqualByComparingTo("-35");

        // The rest on account is allocated later; an allocation is undone.
        int version = acc.version(b, "/payments/" + payment);
        String allocated = expect(
                        acc.action(
                                b,
                                b.session(),
                                "/payments/" + payment + "/allocations",
                                version,
                                "alloc-" + payment,
                                Map.of("allocations", List.of(Map.of("openItemId", secondItem, "amount", "25")))),
                        200)
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(allocated, "$.unallocatedAmount")).isEqualTo("35.0000");
        assertThat(acc.openItem(b, "receivables", second)).containsEntry("status", "SETTLED");
        acc.action(
                        b,
                        b.session(),
                        "/payments/" + payment + "/allocations",
                        version + 1,
                        "alloc-more-" + payment,
                        Map.of("allocations", List.of(Map.of("openItemId", secondItem, "amount", "1"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ALLOCATION_INVALID"));
        List<String> allocationIds = JsonPath.read(acc.body(b, "/receivables/" + secondItem), "$.allocations[*].id");
        assertThat(allocationIds).hasSize(2);
        mvc.perform(unsafe(delete(b.path("/payment-allocations/" + allocationIds.getLast())))
                        .cookie(b.session())
                        .header("Idempotency-Key", "unalloc-" + allocationIds.getLast()))
                .andExpect(status().isNoContent());
        assertThat(acc.openItem(b, "receivables", second)).containsEntry("openAmount", "25.0000");
        assertThat(acc.balance(b, "1100")).isEqualByComparingTo("-35");

        // Voiding undoes the allocations and reverses the receipt.
        version = acc.version(b, "/payments/" + payment);
        acc.action(
                        b,
                        b.session(),
                        "/payments/" + payment + "/void",
                        version,
                        "void-blank-" + payment,
                        Map.of("reason", " "))
                .andExpect(status().isUnprocessableContent());
        expect(
                acc.action(
                        b,
                        b.session(),
                        "/payments/" + payment + "/void",
                        version,
                        "void-" + payment,
                        Map.of("reason", "Bounced")),
                200);
        assertThat(acc.<String>read(b, "/payments/" + payment, "$.status")).isEqualTo("VOIDED");
        assertThat(acc.openItem(b, "receivables", first)).containsEntry("openAmount", "110.0000");
        assertThat(acc.openItem(b, "receivables", second)).containsEntry("openAmount", "55.0000");
        assertThat(acc.openItem(b, "receivables", payment)).containsEntry("status", "VOIDED");
        assertThat(acc.balance(b, "1010")).isEqualByComparingTo("0");
        assertThat(acc.balance(b, "1100")).isEqualByComparingTo("165");
        acc.action(
                        b,
                        b.session(),
                        "/payments/" + payment + "/void",
                        version + 1,
                        "void-again-" + payment,
                        Map.of("reason", "x"))
                .andExpect(status().isConflict());
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    @Test
    void aCreditNoteIsNettedAgainstItsInvoice() throws Exception {
        O2C o = sales.setup();
        Books b = acc.books(o.inv().company());
        UUID service = sales.service(o, "SVC");
        UUID invoice = invoice(o, o.customer(), service, "4");
        UUID invoiceLine = sales.ids(o, "/invoices/" + invoice, "$.lines[*].id").getFirst();
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
                                "lines",
                                List.of(OrgFixtures.map("originalInvoiceLineId", invoiceLine, "quantity", "1")))),
                201);
        expect(sales.action(o, o.session(), "/invoices/" + creditNote + "/post", 0, "post-" + creditNote, null), 200);
        UUID invoiceItem = itemId(b, "receivables", invoice);
        UUID creditItem = itemId(b, "receivables", creditNote);
        assertThat(acc.openItem(b, "receivables", creditNote)).containsEntry("openAmount", "-27.5000");

        mvc.perform(unsafe(post(b.path("/open-items/net")))
                        .cookie(b.session())
                        .header("Idempotency-Key", "net-" + invoice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "debitItemId", invoiceItem, "creditItemId", creditItem, "amount", "27.50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("27.5000"));
        assertThat(acc.openItem(b, "receivables", invoice)).containsEntry("openAmount", "82.5000");
        assertThat(acc.openItem(b, "receivables", creditNote)).containsEntry("status", "SETTLED");
        // Items of different kinds or reversed roles are not netted.
        mvc.perform(unsafe(post(b.path("/open-items/net")))
                        .cookie(b.session())
                        .header("Idempotency-Key", "net-wrong-" + invoice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "debitItemId", creditItem, "creditItemId", invoiceItem, "amount", "1")))
                .andExpect(status().isUnprocessableContent());
        assertThat(acc.balance(b, "1100")).isEqualByComparingTo("82.5");
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    @Test
    void aForeignCurrencySettlementBooksTheRealizedDifference() throws Exception {
        O2C o = sales.setup();
        Books b = acc.books(o.inv().company());
        sales.rate(o, "EUR", "1.10");
        UUID customer = sales.customer(o.inv(), "EURO1", "EUR", o.terms(), o.taxCode(), null);
        UUID service = sales.service(o, "SVC");
        UUID invoice = invoice(o, customer, service, "2", "20"); // 44 EUR = 48.40 USD
        assertThat(acc.openItem(b, "receivables", invoice))
                .containsEntry("openAmount", "44.0000")
                .containsEntry("openAmountBase", "48.4000");

        // A EUR bank account; the euro has risen to 1.20 when the customer pays.
        UUID gl = id(
                acc.create(
                        b,
                        b.session(),
                        "/accounts",
                        OrgFixtures.map(
                                "code",
                                "1020",
                                "name",
                                "Bank – EUR",
                                "accountType",
                                "ASSET",
                                "accountSubtype",
                                "BANK",
                                "currencyCode",
                                "EUR")),
                201);
        b.accounts().put("1020", gl);
        UUID euroBank = id(
                acc.create(
                        b,
                        b.session(),
                        "/bank-accounts",
                        OrgFixtures.map(
                                "name",
                                "Euro bank",
                                "accountId",
                                gl,
                                "currencyCode",
                                "EUR",
                                "bankName",
                                "Euro Bank",
                                "accountNumber",
                                "87654321")),
                201);
        java.time.LocalDate tomorrow = o.inv().today().plusDays(1);
        id(
                sales.create(
                        o,
                        "/exchange-rates",
                        OrgFixtures.map("currencyCode", "EUR", "rateDate", tomorrow, "rate", "1.20")),
                201);
        Map<String, Object> body = acc.payment(
                b, "INBOUND", customer, euroBank, "44", allocations(itemId(b, "receivables", invoice), "44"));
        body.put("paymentDate", tomorrow);
        UUID payment = acc.postedPayment(b, body);

        assertThat(acc.<String>read(b, "/payments/" + payment, "$.amountBase")).isEqualTo("52.8000");
        assertThat(acc.openItem(b, "receivables", invoice)).containsEntry("status", "SETTLED");
        assertThat(acc.<String>read(
                        b, "/receivables/" + itemId(b, "receivables", invoice), "$.allocations[0].fxDifferenceBase"))
                .isIn("4.4000", "-4.4000");
        assertThat(acc.balance(b, "1100")).isEqualByComparingTo("0");
        assertThat(acc.balance(b, "1020")).isEqualByComparingTo("52.80");
        assertThat(acc.balance(b, "7000")).isEqualByComparingTo("-4.40"); // a gain is a credit
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    @Test
    void aSupplierPaymentSettlesTheBill() throws Exception {
        P2P p = proc.setup();
        Books b = acc.books(p.inv().company());
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "5.00", p.taxCode()));
        UUID receipt = proc.postedReceipt(p, order, null);
        UUID bill = UUID.fromString(JsonPath.read(
                expect(
                                mvc.perform(unsafe(post(p.path("/supplier-bills/from-receipts")))
                                        .cookie(p.session())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(OrgFixtures.json(
                                                "goodsReceiptIds", List.of(receipt),
                                                "supplierInvoiceNumber", "INV-9",
                                                "billDate", p.inv().today()))),
                                201)
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        expect(proc.action(p, p.session(), "/supplier-bills/" + bill + "/post", 0, "post-" + bill, null), 200);
        UUID item = itemId(b, "payables", bill);
        // A receipt cannot settle a bill, nor a payment a customer's invoice.
        acc.create(
                        b,
                        b.session(),
                        "/payments",
                        acc.payment(b, "INBOUND", p.supplier(), b.bankAccount(), "55", Map.of()))
                .andExpect(status().isUnprocessableContent());

        UUID payment = acc.postedPayment(
                b, acc.payment(b, "OUTBOUND", p.supplier(), b.bankAccount(), "55", allocations(item, "55")));
        assertThat(acc.<String>read(b, "/payments/" + payment, "$.number")).startsWith("PAY-");
        assertThat(acc.openItem(b, "payables", bill)).containsEntry("status", "SETTLED");
        assertThat(proc.body(p, "/supplier-bills/" + bill + "/settlement")).contains("\"SETTLED\"");
        assertThat(acc.balance(b, "2000")).isEqualByComparingTo("0");
        assertThat(acc.balance(b, "1010")).isEqualByComparingTo("-55");
        assertThat(invariants.check(b.company()).clean()).isTrue();
    }

    // ------------------------------------------------------------------------------ helpers

    private UUID invoice(O2C o, UUID customer, UUID variant, String quantity) throws Exception {
        return invoice(o, customer, variant, quantity, "25");
    }

    /** A posted invoice of a confirmed service order. */
    private UUID invoice(O2C o, UUID customer, UUID variant, String quantity, String price) throws Exception {
        UUID order = id(
                sales.create(
                        o,
                        "/sales-orders",
                        OrgFixtures.map(
                                "customerId",
                                customer,
                                "warehouseId",
                                o.warehouse(),
                                "lines",
                                List.of(sales.line(variant, quantity, price)))),
                201);
        expect(sales.action(o, o.session(), "/sales-orders/" + order + "/confirm", 0, "confirm-" + order, null), 200);
        return sales.postedInvoice(o, order);
    }

    private UUID itemId(Books b, String kind, UUID source) throws Exception {
        return UUID.fromString((String) acc.openItem(b, kind, source).get("id"));
    }

    private static Map<UUID, String> allocations(Object... pairs) {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((UUID) pairs[i], (String) pairs[i + 1]);
        }
        return result;
    }
}
