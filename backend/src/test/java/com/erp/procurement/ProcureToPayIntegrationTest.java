package com.erp.procurement;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.procurement.events.GoodsReceiptPosted;
import com.erp.procurement.events.PurchaseOrderApproved;
import com.erp.procurement.events.SupplierBillPosted;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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

/**
 * The full procure-to-pay flow through the API (DEVELOPMENT_PLAN.md Phase 6 exit criterion):
 * requisition → order → approval → partial receipts → bill with match → return → debit note.
 */
@RecordApplicationEvents
class ProcureToPayIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    ApplicationEvents events;

    private P2P p;

    @BeforeEach
    void setUp() throws Exception {
        p = proc.setup();
    }

    @Test
    void requisitionToDebitNote() throws Exception {
        UUID ea = inv.uom("EA");
        String year = String.valueOf(p.inv().today().getYear());

        // Requisition: created and submitted by the buyer, approved by someone else.
        UUID requisition = id(
                mvc.perform(unsafe(post(p.path("/purchase-requisitions")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(OrgFixtures.map(
                                "branchId", p.inv().branch(),
                                "lines",
                                        List.of(OrgFixtures.map(
                                                "variantId",
                                                p.inv().variant(),
                                                "quantity",
                                                "10",
                                                "uomId",
                                                ea,
                                                "estimatedUnitPrice",
                                                "5.00")))))),
                201);
        expect(proc.action(p, p.session(), "/purchase-requisitions/" + requisition + "/submit", 0, null, null), 200)
                .getResponse();
        assertThat((String) JsonPath.read(proc.body(p, "/purchase-requisitions/" + requisition), "$.number"))
                .isEqualTo("PR-" + year + "-000001");
        // The requester cannot approve their own requisition (G-17).
        proc.action(p, p.session(), "/purchase-requisitions/" + requisition + "/approve", 1, null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOD_VIOLATION"));
        expect(proc.action(p, p.approver(), "/purchase-requisitions/" + requisition + "/approve", 1, null, null), 200);

        // Conversion into a draft order for the supplier.
        String converted = expect(
                        proc.action(
                                p,
                                p.session(),
                                "/purchase-requisitions/" + requisition + "/convert",
                                2,
                                "convert-" + requisition,
                                OrgFixtures.json(
                                        "supplierId",
                                        p.supplier(),
                                        "warehouseId",
                                        p.inv().warehouse().id())),
                        201)
                .getResponse()
                .getContentAsString();
        UUID order = UUID.fromString(JsonPath.read(converted, "$.id"));
        assertThat((String) JsonPath.read(converted, "$.status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(converted, "$.lines[0].unitPrice")).isEqualTo("5.000000");
        mvc.perform(get(p.path("/purchase-requisitions/" + requisition)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("ORDERED"))
                .andExpect(jsonPath("$.lines[0].orderedQuantityBase").value("10.000000"));

        // The buyer adds the tax code; the server reprices.
        String lineId = JsonPath.read(converted, "$.lines[0].id");
        String requisitionLine = JsonPath.read(converted, "$.lines[0].requisitionLineId");
        mvc.perform(unsafe(patch(p.path("/purchase-orders/" + order)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.JSON_MAPPER.writeValueAsString(Map.of(
                                "lines",
                                List.of(OrgFixtures.map(
                                        "variantId",
                                        p.inv().variant().toString(),
                                        "quantity",
                                        "10",
                                        "uomId",
                                        ea.toString(),
                                        "unitPrice",
                                        "5.00",
                                        "taxCodeId",
                                        p.taxCode().toString(),
                                        "requisitionLineId",
                                        requisitionLine))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value("50.0000"))
                .andExpect(jsonPath("$.taxTotal").value("5.0000"))
                .andExpect(jsonPath("$.total").value("55.0000"));
        assertThat(lineId).isNotNull();

        events.clear();
        expect(proc.action(p, p.session(), "/purchase-orders/" + order + "/submit", 1, null, null), 200);
        // Neither the creator nor the submitter approves.
        proc.action(p, p.session(), "/purchase-orders/" + order + "/approve", 2, "self-approve-" + order, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOD_VIOLATION"));
        expect(
                proc.action(p, p.approver(), "/purchase-orders/" + order + "/approve", 2, "approve-" + order, null),
                200);
        assertThat(events.stream(PurchaseOrderApproved.class)).singleElement().satisfies(e -> {
            assertThat(e.number()).isEqualTo("PO-" + year + "-000001");
            assertThat(e.total()).isEqualByComparingTo("55.00");
        });
        UUID orderLine = proc.orderLines(p, order).getFirst();

        // Two partial receipts at the order's net price.
        UUID first = proc.postedReceipt(p, order, List.of(proc.receiptLine(orderLine, "4")));
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[0].receivedQuantityBase").value("4.000000"));
        UUID second = proc.postedReceipt(p, order, null);
        mvc.perform(get(p.path("/goods-receipts/" + second)).cookie(p.session()))
                .andExpect(jsonPath("$.number").value("GRN-" + year + "-000002"))
                .andExpect(jsonPath("$.lines[0].quantityBase").value("6.000000"))
                .andExpect(jsonPath("$.lines[0].unitCostBase").value("5.000000"))
                .andExpect(jsonPath("$.lines[0].valueBase").value("30.0000"));
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        assertThat(inv.balance(p.inv(), p.inv().variant(), p.inv().warehouse().stock()))
                .isEqualByComparingTo("10");
        assertThat(events.stream(GoodsReceiptPosted.class)).hasSize(2);
        // A posted receipt is final.
        proc.action(p, p.session(), "/goods-receipts/" + first + "/post", 1, "again-" + first, null)
                .andExpect(status().isConflict());

        // The bill is prefilled from the receipts, matches, and closes the order.
        String bill = expect(
                        mvc.perform(unsafe(post(p.path("/supplier-bills/from-receipts")))
                                .cookie(p.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(OrgFixtures.json(
                                        "goodsReceiptIds", List.of(first, second),
                                        "supplierInvoiceNumber", "INV-100",
                                        "billDate", p.inv().today()))),
                        201)
                .getResponse()
                .getContentAsString();
        UUID billId = UUID.fromString(JsonPath.read(bill, "$.id"));
        assertThat((String) JsonPath.read(bill, "$.total")).isEqualTo("55.0000");
        assertThat((String) JsonPath.read(bill, "$.dueDate"))
                .isEqualTo(p.inv().today().plusDays(30).toString());
        mvc.perform(unsafe(post(p.path("/supplier-bills/" + billId + "/check-match")))
                        .cookie(p.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchStatus").value("MATCHED"))
                .andExpect(jsonPath("$.issues.length()").value(0));
        events.clear();
        expect(proc.action(p, p.session(), "/supplier-bills/" + billId + "/post", 1, "post-" + billId, null), 200);
        mvc.perform(get(p.path("/supplier-bills/" + billId)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.number").value("BILL-" + year + "-000001"))
                .andExpect(jsonPath("$.lines[0].receiptValueBase").value("20.0000"))
                .andExpect(jsonPath("$.lines[1].receiptValueBase").value("30.0000"));
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.billingStatus").value("BILLED"));
        assertThat(events.stream(SupplierBillPosted.class)).singleElement().satisfies(e -> {
            assertThat(e.metadata().eventType()).isEqualTo(SupplierBillPosted.BILL_TYPE);
            assertThat(e.totals().totalBase()).isEqualByComparingTo("55.00");
            assertThat(e.lines()).extracting(SupplierBillPosted.Line::type).containsOnly("STOCK_RECEIVED");
            assertThat(e.lines().stream()
                            .map(SupplierBillPosted.Line::receiptValueBase)
                            .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("50.00");
            assertThat(e.taxLines())
                    .singleElement()
                    .satisfies(t -> assertThat(t.taxBase()).isEqualByComparingTo("5.00"));
        });
        mvc.perform(get(p.path("/supplier-bills/" + billId + "/settlement")).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openAmount").value("55.0000"));

        // Two units go back to the supplier and are credited by a debit note.
        UUID secondLine = proc.receiptLines(p, second).getFirst();
        UUID purchaseReturn = id(
                mvc.perform(unsafe(post(p.path("/purchase-returns")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "goodsReceiptId",
                                second,
                                "reason",
                                "Damaged",
                                "lines",
                                List.of(OrgFixtures.map("goodsReceiptLineId", secondLine, "quantity", "2"))))),
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
        assertThat(inv.balance(p.inv(), p.inv().variant(), p.inv().warehouse().stock()))
                .isEqualByComparingTo("8");
        mvc.perform(get(p.path("/purchase-returns/" + purchaseReturn)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.lines[0].valueBase").value("10.0000"));
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.lines[0].returnedQuantityBase").value("2.000000"));

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
                                billId,
                                "lines",
                                List.of(OrgFixtures.map(
                                        "goodsReceiptLineId",
                                        secondLine,
                                        "quantity",
                                        "2",
                                        "unitPrice",
                                        "5.00",
                                        "taxCodeId",
                                        p.taxCode()))))),
                201);
        events.clear();
        expect(proc.action(p, p.session(), "/supplier-bills/" + note + "/post", 0, "post-" + note, null), 200);
        mvc.perform(get(p.path("/supplier-bills/" + note)).cookie(p.session()))
                .andExpect(jsonPath("$.number").value("DN-" + year + "-000001"))
                .andExpect(jsonPath("$.total").value("11.0000"))
                .andExpect(jsonPath("$.lines[0].receiptValueBase").value("10.0000"));
        mvc.perform(get(p.path("/goods-receipts/" + second)).cookie(p.session()))
                .andExpect(jsonPath("$.lines[0].creditedQuantityBase").value("2.000000"));
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.lines[0].billedQuantityBase").value("8.000000"));
        assertThat(events.stream(SupplierBillPosted.class)).singleElement().satisfies(e -> {
            assertThat(e.metadata().eventType()).isEqualTo(SupplierBillPosted.DEBIT_NOTE_TYPE);
            assertThat(e.originalBillId()).isEqualTo(billId);
        });
        // Nothing more can be credited for that line.
        UUID tooMuch = id(
                mvc.perform(unsafe(post(p.path("/supplier-bills")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "documentType",
                                "DEBIT_NOTE",
                                "supplierId",
                                p.supplier(),
                                "supplierInvoiceNumber",
                                "CN-8",
                                "billDate",
                                p.inv().today(),
                                "originalBillId",
                                billId,
                                "lines",
                                List.of(OrgFixtures.map(
                                        "goodsReceiptLineId", secondLine, "quantity", "1", "unitPrice", "5.00"))))),
                201);
        proc.action(p, p.session(), "/supplier-bills/" + tooMuch + "/post", 0, "post-" + tooMuch, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_REMAINING"));
    }
}
