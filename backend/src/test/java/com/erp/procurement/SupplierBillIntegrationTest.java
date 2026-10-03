package com.erp.procurement;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Supplier bills: three-way match, duplicates, direct and service bills, debit notes (PRC-3 to PRC-6). */
class SupplierBillIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    AuthTestSupport auth;

    private P2P p;
    private UUID receiptLine;

    @BeforeEach
    void setUp() throws Exception {
        p = proc.setup();
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "4.00", p.taxCode()));
        UUID receipt = proc.postedReceipt(p, order, null);
        receiptLine = proc.receiptLines(p, receipt).getFirst();
    }

    @Test
    void priceDifferencesBlockPostingUntilOverridden() throws Exception {
        UUID bill = id(bill("INV-1", billLine(receiptLine, "10", "4.40")), 201);
        mvc.perform(unsafe(post(p.path("/supplier-bills/" + bill + "/check-match")))
                        .cookie(p.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchStatus").value("EXCEPTION"))
                .andExpect(jsonPath("$.issues[0].problem").value("PRICE"))
                .andExpect(jsonPath("$.issues[0].expected").value("4.0000000000"));
        proc.action(p, p.session(), "/supplier-bills/" + bill + "/post", 1, "post-" + bill, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MATCH_EXCEPTION"));

        // Overriding needs procurement.supplier_bill.override_match and a reason.
        TestUser clerk = auth.user();
        auth.assign(
                clerk,
                auth.customRole("procurement.supplier_bill.read", "procurement.supplier_bill.post"),
                p.inv().company());
        Cookie clerkSession = auth.login(clerk);
        proc.action(
                        p,
                        clerkSession,
                        "/supplier-bills/" + bill + "/override-match",
                        1,
                        null,
                        OrgFixtures.json("reason", "Agreed"))
                .andExpect(status().isForbidden());
        proc.action(
                        p,
                        p.session(),
                        "/supplier-bills/" + bill + "/override-match",
                        1,
                        null,
                        OrgFixtures.json("reason", "Price increase agreed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchStatus").value("OVERRIDDEN"))
                .andExpect(jsonPath("$.matchOverrideReason").value("Price increase agreed"));
        proc.action(p, clerkSession, "/supplier-bills/" + bill + "/post", 2, "post-ok-" + bill, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                // The receipt value (GRNI) is cleared at the receipt cost; the difference is price variance.
                .andExpect(jsonPath("$.lines[0].netAmountBase").value("44.0000"))
                .andExpect(jsonPath("$.lines[0].receiptValueBase").value("40.0000"));
    }

    @Test
    void tolerancesAcceptSmallDifferences() throws Exception {
        mvc.perform(unsafe(put(p.path("/settings/procurement")))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("priceMatchTolerancePercent", "5", "qtyMatchTolerancePercent", "10")))
                .andExpect(status().isOk());
        UUID bill = id(bill("INV-2", billLine(receiptLine, "11", "4.10")), 201);
        mvc.perform(unsafe(post(p.path("/supplier-bills/" + bill + "/check-match")))
                        .cookie(p.session())
                        .header("If-Match", etag(0)))
                .andExpect(jsonPath("$.matchStatus").value("MATCHED"));
        proc.action(p, p.session(), "/supplier-bills/" + bill + "/post", 1, "post-" + bill, null)
                .andExpect(status().isOk())
                // Over-billing within the tolerance never clears more than the receipt value.
                .andExpect(jsonPath("$.lines[0].receiptValueBase").value("40.0000"));
    }

    @Test
    void aSuppliersInvoiceIsRecordedOnce() throws Exception {
        UUID first = id(bill("INV-9", billLine(receiptLine, "5", "4.00")), 201);
        bill("inv-9", billLine(receiptLine, "5", "4.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER_INVOICE"));
        // Cancelling the draft frees the number.
        expect(proc.action(p, p.session(), "/supplier-bills/" + first + "/cancel", 0, null, null), 200);
        bill("INV-9", billLine(receiptLine, "5", "4.00")).andExpect(status().isCreated());
    }

    @Test
    void servicesAreBilledWithoutReceiptButStockableGoodsNeedOne() throws Exception {
        UUID service = serviceVariant();
        Map<String, Object> serviceLine = proc.orderLine(service, "2", "50", null);
        serviceLine.put("uomId", inv.uom("H"));
        UUID order = proc.approvedOrder(p, serviceLine, proc.orderLine(p.inv().variant(), "1", "4.00", null));
        List<UUID> lines = proc.orderLines(p, order);

        // PRC-4: a stockable order line is billed from its receipt.
        bill("INV-S1", OrgFixtures.map("purchaseOrderLineId", lines.get(1), "quantity", "1", "unitPrice", "4.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_REQUIRED"));
        UUID bill = id(
                bill(
                        "INV-S2",
                        OrgFixtures.map("purchaseOrderLineId", lines.get(0), "quantity", "2", "unitPrice", "50")),
                201);
        proc.action(p, p.session(), "/supplier-bills/" + bill + "/post", 0, "post-" + bill, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lineKind").value("SERVICE"))
                .andExpect(jsonPath("$.lines[0].receiptValueBase").doesNotExist());
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.lines[0].billedQuantityBase").value("2.000000"))
                .andExpect(jsonPath("$.billingStatus").value("PARTIALLY_BILLED"));
    }

    @Test
    void directBillsNeedTheirPermissionAndAreForServicesOnly() throws Exception {
        UUID service = serviceVariant();
        Map<String, Object> direct =
                OrgFixtures.map("variantId", service, "quantity", "1", "uomId", inv.uom("H"), "unitPrice", "80");
        TestUser clerk = auth.user();
        auth.assign(
                clerk,
                auth.customRole("procurement.supplier_bill.read", "procurement.supplier_bill.create"),
                p.inv().company());
        Cookie clerkSession = auth.login(clerk);
        mvc.perform(unsafe(post(p.path("/supplier-bills")))
                        .cookie(clerkSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(billBody("D-1", direct)))
                .andExpect(status().isForbidden());
        bill("D-2", OrgFixtures.map("variantId", p.inv().variant(), "quantity", "1", "unitPrice", "4"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_REQUIRED"));
        UUID bill = id(bill("D-3", direct), 201);
        proc.action(p, p.session(), "/supplier-bills/" + bill + "/post", 0, "post-" + bill, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseOrderId").doesNotExist());
        // A blocked supplier gets no new direct bills.
        mvc.perform(unsafe(post(p.path("/partners/" + p.supplier() + "/block")))
                        .cookie(p.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk());
        bill("D-4", direct)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PARTNER_BLOCKED"));
    }

    @Test
    void debitNotesNeverExceedTheirBill() throws Exception {
        UUID bill = id(bill("INV-D", billLine(receiptLine, "10", "4.00")), 201);
        expect(proc.action(p, p.session(), "/supplier-bills/" + bill + "/post", 0, "post-" + bill, null), 200);
        UUID service = serviceVariant();
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
                                "CN-1",
                                "billDate",
                                p.inv().today(),
                                "originalBillId",
                                bill,
                                "lines",
                                List.of(OrgFixtures.map(
                                        "variantId",
                                        service,
                                        "quantity",
                                        "1",
                                        "uomId",
                                        inv.uom("H"),
                                        "unitPrice",
                                        "45"))))),
                201);
        proc.action(p, p.session(), "/supplier-bills/" + note + "/post", 0, "post-" + note, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DEBIT_NOTE_EXCEEDS_BILL"));
        // A debit note needs a posted bill of the same supplier.
        mvc.perform(unsafe(post(p.path("/supplier-bills")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "documentType", "DEBIT_NOTE",
                                "supplierId", p.supplier(),
                                "supplierInvoiceNumber", "CN-2",
                                "billDate", p.inv().today(),
                                "originalBillId", UUID.randomUUID(),
                                "lines",
                                        List.of(OrgFixtures.map(
                                                "variantId",
                                                service,
                                                "quantity",
                                                "1",
                                                "uomId",
                                                inv.uom("H"),
                                                "unitPrice",
                                                "1")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/originalBillId"));
    }

    @Test
    void foreignCurrencyBillsCarryBaseAmountsPerLine() throws Exception {
        expect(
                mvc.perform(unsafe(post(p.path("/exchange-rates")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "currencyCode", "EUR", "rateDate", p.inv().today(), "rate", "1.2345"))),
                201);
        UUID euro = proc.supplier(p.inv(), "EUROCO", "EUR", null, null);
        UUID service = serviceVariant();
        String created = expect(
                        mvc.perform(unsafe(post(p.path("/supplier-bills")))
                                .cookie(p.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(OrgFixtures.json(
                                        "documentType",
                                        "BILL",
                                        "supplierId",
                                        euro,
                                        "supplierInvoiceNumber",
                                        "E-1",
                                        "billDate",
                                        p.inv().today(),
                                        "lines",
                                        List.of(
                                                OrgFixtures.map(
                                                        "variantId",
                                                        service,
                                                        "quantity",
                                                        "1",
                                                        "uomId",
                                                        inv.uom("H"),
                                                        "unitPrice",
                                                        "10.01",
                                                        "taxCodeId",
                                                        p.taxCode()),
                                                OrgFixtures.map(
                                                        "variantId",
                                                        service,
                                                        "quantity",
                                                        "1",
                                                        "uomId",
                                                        inv.uom("H"),
                                                        "unitPrice",
                                                        "10.01",
                                                        "taxCodeId",
                                                        p.taxCode()))))),
                        201)
                .getResponse()
                .getContentAsString();
        // Each line: net 10.01 EUR → 12.36 USD (12.357345); tax 1.00 EUR → 1.23 USD; header = sums of lines.
        mvc.perform(get(p.path("/supplier-bills/" + JsonPath.read(created, "$.id")))
                        .cookie(p.session()))
                .andExpect(jsonPath("$.currencyCode").value("EUR"))
                .andExpect(jsonPath("$.total").value("22.0200"))
                .andExpect(jsonPath("$.lines[0].netAmountBase").value("12.3600"))
                .andExpect(jsonPath("$.subtotalBase").value("24.7200"))
                .andExpect(jsonPath("$.taxTotalBase").value("2.4600"))
                .andExpect(jsonPath("$.totalBase").value("27.1800"))
                .andExpect(jsonPath("$.taxes[0].taxAmountBase").value("2.4600"));
    }

    private Map<String, Object> billLine(UUID goodsReceiptLine, String quantity, String unitPrice) {
        return OrgFixtures.map(
                "goodsReceiptLineId",
                goodsReceiptLine,
                "quantity",
                quantity,
                "unitPrice",
                unitPrice,
                "taxCodeId",
                p.taxCode());
    }

    private ResultActions bill(String invoiceNumber, Map<String, Object> line) throws Exception {
        return mvc.perform(unsafe(post(p.path("/supplier-bills")))
                .cookie(p.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(billBody(invoiceNumber, line)));
    }

    private String billBody(String invoiceNumber, Map<String, Object> line) throws Exception {
        return OrgFixtures.JSON_MAPPER.writeValueAsString(OrgFixtures.map(
                "documentType", "BILL",
                "supplierId", p.supplier(),
                "supplierInvoiceNumber", invoiceNumber,
                "billDate", p.inv().today(),
                "lines", List.of(line)));
    }

    private UUID serviceVariant() throws Exception {
        String product = expect(
                        mvc.perform(unsafe(post(p.path("/products")))
                                .cookie(p.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(OrgFixtures.json(
                                        "code",
                                        "SVC-"
                                                + UUID.randomUUID()
                                                        .toString()
                                                        .substring(0, 6)
                                                        .toUpperCase(),
                                        "name",
                                        "Service",
                                        "categoryId",
                                        p.inv().category(),
                                        "productType",
                                        "SERVICE",
                                        "baseUomId",
                                        inv.uom("H")))),
                        201)
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(product, "$.variants[0].id"));
    }
}
