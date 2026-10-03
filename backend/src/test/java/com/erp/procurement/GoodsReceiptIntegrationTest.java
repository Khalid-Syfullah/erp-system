package com.erp.procurement;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Warehouse;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
import com.erp.support.TestDatabase;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Goods receipts and purchase returns: quantities, valuation, duplicates, rollback (PRC-1, PRC-2). */
class GoodsReceiptIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    InventoryFixtures inv;

    private P2P p;

    @BeforeEach
    void setUp() throws Exception {
        p = proc.setup();
    }

    @Test
    void foreignCurrencyReceiptsAreValuedAtTheReceiptDateRate() throws Exception {
        expect(
                mvc.perform(unsafe(post(p.path("/exchange-rates")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "currencyCode",
                                "EUR",
                                "rateDate",
                                p.inv().today().minusDays(3),
                                "rate",
                                "1.10"))),
                201);
        UUID euroSupplier = proc.supplier(p.inv(), "EUROCO", "EUR", null, null);
        Map<String, Object> line = proc.orderLine(p.inv().variant(), "10", "3.00", null);
        line.put("discountPercent", "10");
        Map<String, Object> body = proc.order(p, line);
        body.put("supplierId", euroSupplier);
        UUID order = id(proc.createOrder(p, body), 201);
        expect(proc.action(p, p.session(), "/purchase-orders/" + order + "/submit", 0, null, null), 200);
        expect(
                proc.action(p, p.approver(), "/purchase-orders/" + order + "/approve", 1, "approve-" + order, null),
                200);
        UUID orderLine = proc.orderLines(p, order).getFirst();

        UUID receipt = proc.postedReceipt(p, order, List.of(proc.receiptLine(orderLine, "4")));
        // Net unit price 2.70 EUR × 1.10 = 2.97 USD.
        mvc.perform(get(p.path("/goods-receipts/" + receipt)).cookie(p.session()))
                .andExpect(jsonPath("$.currencyCode").value("EUR"))
                .andExpect(jsonPath("$.exchangeRate").value("1.1000000000"))
                .andExpect(jsonPath("$.lines[0].unitCostDoc").value("2.700000"))
                .andExpect(jsonPath("$.lines[0].unitCostBase").value("2.970000"))
                .andExpect(jsonPath("$.lines[0].valueBase").value("11.8800"));
        mvc.perform(get(p.path("/stock-valuation")).cookie(p.session()))
                .andExpect(jsonPath("$.totalValueBase").value("11.8800"));
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"));
    }

    @Test
    void receiptsNeverExceedTheOpenQuantityBeyondTheTolerance() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "1", null));
        UUID line = proc.orderLines(p, order).getFirst();
        UUID tooMuch = id(proc.createReceipt(p, order, List.of(proc.receiptLine(line, "11"))), 201);
        proc.action(p, p.session(), "/goods-receipts/" + tooMuch + "/post", 0, "too-much-" + tooMuch, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_REMAINING"))
                .andExpect(jsonPath("$.errors[0].pointer").value("/lines/0/quantity"))
                .andExpect(jsonPath("$.errors[0].meta.open").value("10"));

        // With a 10 % over-receipt tolerance, 11 is fine (and 12 would not be).
        mvc.perform(unsafe(put(p.path("/settings/inventory")))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("overReceiptTolerancePercent", "10")))
                .andExpect(status().isOk());
        UUID twelve = id(proc.createReceipt(p, order, List.of(proc.receiptLine(line, "12"))), 201);
        proc.action(p, p.session(), "/goods-receipts/" + twelve + "/post", 0, "twelve-" + twelve, null)
                .andExpect(status().isUnprocessableContent());
        expect(proc.action(p, p.session(), "/goods-receipts/" + tooMuch + "/post", 0, "eleven-" + tooMuch, null), 200);
        assertThat(inv.balance(p.inv(), p.inv().variant(), p.inv().warehouse().stock()))
                .isEqualByComparingTo("11");
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        // Nothing is open any more, even within the tolerance.
        proc.createReceipt(p, order, null).andExpect(status().isConflict());
    }

    @Test
    void aReceiptIsPostedOnlyOnce() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "5", "1", null));
        UUID receipt = id(proc.createReceipt(p, order, null), 201);
        var first = expect(
                proc.action(p, p.session(), "/goods-receipts/" + receipt + "/post", 0, "once-" + receipt, null), 200);
        // A retried request with the same key replays the response; nothing is received twice.
        proc.action(p, p.session(), "/goods-receipts/" + receipt + "/post", 0, "once-" + receipt, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.number")
                        .value((String) JsonPath.read(first.getResponse().getContentAsString(), "$.number")));
        // A new request is refused: the receipt is posted.
        proc.action(p, p.session(), "/goods-receipts/" + receipt + "/post", 1, "twice-" + receipt, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(inv.balance(p.inv(), p.inv().variant(), p.inv().warehouse().stock()))
                .isEqualByComparingTo("5");
        mvc.perform(get(p.path("/stock-movements"))
                        .cookie(p.session())
                        .param("filter[movementType]", "PURCHASE_RECEIPT"))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void aFailingStockPostingLeavesNoTrace() throws Exception {
        Warehouse other = inv.warehouse(p.inv(), "WH2");
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "5", "1", null));
        UUID line = proc.orderLines(p, order).getFirst();
        Map<String, Object> elsewhere = proc.receiptLine(line, "5");
        elsewhere.put("locationId", other.stock());
        UUID receipt = id(proc.createReceipt(p, order, List.of(elsewhere)), 201);
        proc.action(p, p.session(), "/goods-receipts/" + receipt + "/post", 0, "fail-" + receipt, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/lines/0/toLocationId"));

        mvc.perform(get(p.path("/goods-receipts/" + receipt)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.number").doesNotExist());
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lines[0].receivedQuantityBase").value("0.000000"));
        assertThat(inv.balance(p.inv(), p.inv().variant(), other.stock())).isEqualByComparingTo("0");
        // No receipt or stock movement number was used.
        UUID good = proc.postedReceipt(p, order, null);
        assertThat((String) JsonPath.read(proc.body(p, "/goods-receipts/" + good), "$.number"))
                .endsWith("-000001");
    }

    @Test
    void onlyApprovedOrdersOfStockableGoodsAreReceived() throws Exception {
        UUID draft = id(proc.createOrder(p, proc.order(p, proc.orderLine(p.inv().variant(), "1", "1", null))), 201);
        proc.createReceipt(p, draft, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "2", "1", null));
        UUID line = proc.orderLines(p, order).getFirst();
        proc.createReceipt(p, order, List.of(proc.receiptLine(line, "1"), proc.receiptLine(line, "1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_LINE"));
        proc.createReceipt(p, order, List.of(proc.receiptLine(UUID.randomUUID(), "1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_LINE"));
        // Drafts can be cancelled; a cancelled draft cannot be posted.
        UUID receipt = id(proc.createReceipt(p, order, null), 201);
        expect(proc.action(p, p.session(), "/goods-receipts/" + receipt + "/cancel", 0, null, null), 200);
        proc.action(p, p.session(), "/goods-receipts/" + receipt + "/post", 1, "cancelled-" + receipt, null)
                .andExpect(status().isConflict());
    }

    @Test
    void returnsShipBackReceivedGoodsAndReopenTheOrder() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "10", "2", null));
        UUID receipt = proc.postedReceipt(p, order, null);
        UUID receiptLine = proc.receiptLines(p, receipt).getFirst();
        mvc.perform(unsafe(post(p.path("/purchase-returns")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "goodsReceiptId",
                                receipt,
                                "reason",
                                "Too many",
                                "lines",
                                List.of(OrgFixtures.map("goodsReceiptLineId", receiptLine, "quantity", "11")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("QUANTITY_EXCEEDS_REMAINING"));
        UUID back = id(
                mvc.perform(unsafe(post(p.path("/purchase-returns")))
                        .cookie(p.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "goodsReceiptId",
                                receipt,
                                "reason",
                                "Faulty",
                                "lines",
                                List.of(OrgFixtures.map("goodsReceiptLineId", receiptLine, "quantity", "3"))))),
                201);
        expect(proc.action(p, p.session(), "/purchase-returns/" + back + "/post", 0, "return-" + back, null), 200);
        assertThat(inv.balance(p.inv(), p.inv().variant(), p.inv().warehouse().stock()))
                .isEqualByComparingTo("7");
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[0].returnedQuantityBase").value("3.000000"));
        mvc.perform(get(p.path("/stock-movements"))
                        .cookie(p.session())
                        .param("filter[movementType]", "PURCHASE_RETURN"))
                .andExpect(jsonPath("$.data.length()").value(1));
        // The returned quantity can be received again.
        proc.postedReceipt(p, order, null);
        assertThat(inv.balance(p.inv(), p.inv().variant(), p.inv().warehouse().stock()))
                .isEqualByComparingTo("10");
        mvc.perform(get(p.path("/purchase-orders/" + order)).cookie(p.session()))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void postedReceiptsAreFrozenInTheDatabase() throws Exception {
        UUID order = proc.approvedOrder(p, proc.orderLine(p.inv().variant(), "2", "1", null));
        UUID receipt = proc.postedReceipt(p, order, null);
        try (Connection app = TestDatabase.connectAs("erp_app", TestDatabase.APP_PASSWORD)) {
            app.setAutoCommit(false);
            try (PreparedStatement context = app.prepareStatement("SELECT set_config('app.company_id', ?, true)")) {
                context.setString(1, p.inv().company().toString());
                context.execute();
            }
            try (Statement statement = app.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                                "UPDATE procurement.goods_receipt_lines SET quantity_base = 99 WHERE goods_receipt_id = '"
                                        + receipt + "'"))
                        .isInstanceOfSatisfying(
                                PSQLException.class,
                                e -> assertThat(e.getServerErrorMessage().getConstraint())
                                        .isEqualTo("ck_goods_receipt_lines__frozen"));
            } finally {
                app.rollback();
            }
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }
}
