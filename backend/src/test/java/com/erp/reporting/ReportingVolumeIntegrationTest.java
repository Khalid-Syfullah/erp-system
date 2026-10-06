package com.erp.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.reporting.application.ReportCatalog;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportSourceKind;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Warehouse;
import com.erp.support.ReportingFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.erp.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 10 exit criterion (DEVELOPMENT_PLAN.md): standard reports return in under 2 s at p95 on
 * seeded volume data at 1/5 of the target volume (ARCHITECTURE.md §10: 10 M journal lines and 10 M
 * stock ledger entries over five years). One company receives 2 M stock ledger entries and 2 M
 * posted journal lines in the current year, 200 k invoice lines, purchase documents, 2 000 employees
 * with attendance and leave. The bulk load is set-based SQL with row triggers off; the application
 * role then posts the 200 k journal entries, so the database still checks every one (balanced, in an
 * open period). Every catalogued report, Accounting's statements and the dashboards then run one
 * period (the current month) through the API: one warm-up and three timed runs each; the slowest
 * must stay under 2 s. The current month of the stock ledger is exported as CSV by streaming.
 */
class ReportingVolumeIntegrationTest extends IntegrationTest {

    static final int LEDGER_ROWS = 2_000_000;
    static final int JOURNAL_ENTRIES = 200_000;
    static final int JOURNAL_LINES_PER_ENTRY = 10;
    static final int VARIANTS = 2_000;
    static final int MOVEMENTS = 20_000;
    static final int CUSTOMERS = 1_000;
    static final int SUPPLIERS = 200;
    static final int INVOICES = 50_000;
    static final int INVOICE_LINES = 4;
    static final int ORDERS = 5_000;
    static final int PURCHASE_ORDERS = 8_000;
    static final int EMPLOYEES = 2_000;
    static final int ATTENDANCE_DAYS = 50;
    static final Duration BUDGET = Duration.ofSeconds(2);

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    InventoryFixtures inv;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    ReportingFixtures rep;

    @Autowired
    ReportCatalog catalog;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private UUID company;
    private DSLContext seed;
    private LocalDate yearStart;
    private int days;

    @Test
    void standardReportsStayWithinTwoSecondsOnTheVolumeDataset() throws Exception {
        O2C o = sales.setup();
        company = o.inv().company();
        Books b = acc.books(company);
        LocalDate today = o.inv().today();
        yearStart = today.withDayOfYear(1);
        days = (int) ChronoUnit.DAYS.between(yearStart, today) + 1;
        Warehouse wh2 = inv.warehouse(o.inv(), "WH2");
        Warehouse wh3 = inv.warehouse(o.inv(), "WH3");

        long started = System.nanoTime();
        // The bulk load is one transaction of a superuser session with row triggers and foreign-key checks
        // off (session_replication_role = replica): in the full suite's database, per-row trigger work
        // makes a 2 M-row load crawl. The temporary helper tables live on that session.
        bulkLoad(() -> {
            seedInventory(o, List.of(o.inv().warehouse(), wh2, wh3));
            seedJournal(o.inv().branch());
            seedPartnersAndSales(o, b);
            seedProcurement(o, b);
            seedHr(o.inv().branch(), o.inv().user().id());
        });
        analyze();
        // The application role posts the journal entries: the database checks every one at commit
        // (balanced, lines posted, in an open period), with statistics that let it use the index.
        seeding(() -> exec("""
                UPDATE accounting.journal_entries j SET status = 'POSTED', number = 'VOL-' || x.n, posted_at = now()
                  FROM (SELECT id, row_number() OVER (ORDER BY id) AS n FROM accounting.journal_entries
                         WHERE company_id = ? AND status = 'DRAFT' AND description = 'Volume entry') x
                 WHERE j.id = x.id""", company));
        analyze();
        System.out.println("Volume data seeded in "
                + Duration.ofNanos(System.nanoTime() - started).toSeconds() + " s");
        assertThat(inCompany(() -> dsl.fetchValue(
                        "SELECT count(*) FROM inventory.inventory_transactions WHERE company_id = ?", company)))
                .isEqualTo((long) LEDGER_ROWS + VARIANTS * 3);
        assertThat(inCompany(() -> dsl.fetchValue(
                        "SELECT count(*) FROM accounting.journal_lines WHERE company_id = ? AND is_posted", company)))
                .isEqualTo((long) JOURNAL_ENTRIES * JOURNAL_LINES_PER_ENTRY);

        Cookie everything = rep.user(company, ReportCatalogIntegrationTest.ALL_REPORTS);
        LocalDate monthStart = today.withDayOfMonth(1);
        String month = "from=" + monthStart + "&to=" + today;
        Map<String, Duration> timings = new LinkedHashMap<>();
        for (ReportDefinition definition : catalog.all()) {
            if (definition.source() != ReportSourceKind.VIEWS) {
                continue;
            }
            String query = definition.parameter("from").isPresent()
                            && definition.parameter("from").get().required()
                    ? month
                    : "";
            timings.put(definition.code(), time(everything, path("/reports/" + definition.code()), query));
        }
        // As-of figures from the stock ledger, and the other groupings that read the most data.
        timings.put("stock-on-hand as of", time(everything, path("/reports/stock-on-hand"), "asOf=" + monthStart));
        timings.put("stock-valuation as of", time(everything, path("/reports/stock-valuation"), "asOf=" + monthStart));
        timings.put("stock-on-hand by location", time(everything, path("/reports/stock-on-hand"), "groupBy=LOCATION"));
        timings.put("purchases by month", time(everything, path("/reports/purchases"), month + "&groupBy=MONTH"));
        timings.put(
                "sales-by-period year",
                time(everything, path("/reports/sales-by-period"), "from=" + yearStart + "&to=" + today));
        // Accounting's statements (served by Accounting) on the same volume.
        timings.put("trial-balance", time(everything, path("/reports/trial-balance"), month));
        timings.put("profit-and-loss", time(everything, path("/reports/profit-and-loss"), month));
        timings.put("balance-sheet", time(everything, path("/reports/balance-sheet"), "asOf=" + today));
        timings.put(
                "general-ledger",
                time(everything, path("/reports/general-ledger"), month + "&accountId=" + b.account("6000")));
        timings.put("ar-ageing", time(everything, path("/reports/ar-ageing"), "asOf=" + today));
        timings.put("ap-ageing", time(everything, path("/reports/ap-ageing"), "asOf=" + today));
        timings.put(
                "cash-book", time(everything, path("/reports/cash-book"), month + "&bankAccountId=" + b.bankAccount()));
        timings.put("dashboard executive", time(everything, path("/dashboards/executive"), ""));

        timings.forEach((report, took) -> System.out.printf("%-30s %6d ms%n", report, took.toMillis()));
        assertThat(timings)
                .allSatisfy((report, took) -> assertThat(took).as(report).isLessThan(BUDGET));

        // The current month of the stock ledger streams into a CSV export.
        long exportStarted = System.nanoTime();
        byte[] file = rep.exported(
                everything,
                company,
                "inventory-transactions",
                "CSV",
                Map.of("from", monthStart.toString(), "to", today.toString()));
        Duration exportTook = Duration.ofNanos(System.nanoTime() - exportStarted);
        long expected = inCompany(() -> dsl.fetchValue(
                                "SELECT count(*) FROM inventory.inventory_transactions WHERE company_id = ? AND transaction_date >= ?",
                                company,
                                monthStart))
                        instanceof Long n
                ? n
                : -1;
        long lines = 0;
        for (byte c : file) {
            if (c == '\n') {
                lines++;
            }
        }
        System.out.println("Exported " + (lines - 1) + " ledger rows (" + file.length / 1024 + " KiB) in "
                + exportTook.toMillis() + " ms");
        assertThat(lines - 1).isEqualTo(expected);
        assertThat(exportTook).isLessThan(Duration.ofMinutes(2));
    }

    /** One warm-up and three timed runs; the slowest run (p95 of three is its maximum). */
    private Duration time(Cookie session, String path, String query) throws Exception {
        String url = path + (query.isEmpty() ? "" : "?" + query);
        run(session, url);
        Duration slowest = Duration.ZERO;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            run(session, url);
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            slowest = took.compareTo(slowest) > 0 ? took : slowest;
        }
        return slowest;
    }

    private void run(Cookie session, String url) throws Exception {
        MvcResult result = mvc.perform(get(url).cookie(session)).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError(url + ": " + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
    }

    private String path(String suffix) {
        return ReportingFixtures.path(company, suffix);
    }

    // ------------------------------------------------------------------------------- seeding

    private void seedInventory(O2C o, List<Warehouse> warehouses) {
        UUID uom = inv.uom("EA");
        {
            exec("""
                    INSERT INTO inventory.products (company_id, code, name, category_id, product_type, base_uom_id)
                    SELECT ?, 'P' || lpad(g::text, 5, '0'), 'Product ' || g, ?, 'STOCKABLE', ?
                      FROM generate_series(1, ?) g""", company, o.inv().category(), uom, VARIANTS);
            exec("""
                    INSERT INTO inventory.product_variants (company_id, product_id, sku, name, is_default)
                    SELECT company_id, id, code, name, true FROM inventory.products
                     WHERE company_id = ? AND code LIKE 'P%'""", company);
            exec("""
                    CREATE TEMP TABLE vol_variants ON COMMIT DROP AS
                    SELECT row_number() OVER (ORDER BY v.sku)::int - 1 AS n, v.id
                      FROM inventory.product_variants v WHERE v.company_id = ? AND v.sku LIKE 'P%'""", company);
            exec("ANALYZE vol_variants");
            exec("CREATE TEMP TABLE vol_locations (n int, warehouse uuid, location uuid) ON COMMIT DROP");
            for (int i = 0; i < warehouses.size(); i++) {
                exec(
                        "INSERT INTO vol_locations VALUES (?, ?, ?)",
                        i,
                        warehouses.get(i).id(),
                        warehouses.get(i).stock());
            }
            // 20 000 movements, one line each: receipts, issues, adjustments, scrap and openings.
            exec("""
                    CREATE TEMP TABLE vol_moves ON COMMIT DROP AS
                    SELECT g, uuidv7() AS id, uuidv7() AS line_id,
                           CASE WHEN g % 20 < 8 THEN 'PURCHASE_RECEIPT' WHEN g % 20 < 16 THEN 'SALES_ISSUE'
                                WHEN g % 20 < 18 THEN 'ADJUSTMENT' WHEN g % 20 = 18 THEN 'SCRAP' ELSE 'OPENING' END AS type,
                           ?::date + (g % ?) AS day, g % 3 AS wh, g % ? AS variant
                      FROM generate_series(0, ? - 1) g""", yearStart, days, VARIANTS, MOVEMENTS);
            exec("ANALYZE vol_moves");
            exec("""
                    INSERT INTO inventory.stock_movements (id, company_id, movement_type, status, movement_date, warehouse_id,
                                                           reason_code_id)
                    SELECT m.id, ?, m.type, 'DRAFT', m.day, l.warehouse,
                           CASE m.type WHEN 'ADJUSTMENT' THEN ?::uuid WHEN 'SCRAP' THEN ?::uuid END
                      FROM vol_moves m JOIN vol_locations l ON l.n = m.wh""", company, o.inv().adjustmentReason(), o.inv().scrapReason());
            exec("""
                    INSERT INTO inventory.stock_movement_lines (id, company_id, movement_id, line_no, variant_id, from_location_id,
                                                                to_location_id, quantity, uom_id, quantity_base, unit_cost_base)
                    SELECT m.line_id, ?, m.id, 1, v.id,
                           CASE WHEN m.type IN ('PURCHASE_RECEIPT', 'OPENING') THEN NULL ELSE l.location END,
                           CASE WHEN m.type IN ('PURCHASE_RECEIPT', 'OPENING') THEN l.location END,
                           1, ?, 1, 10
                      FROM vol_moves m JOIN vol_locations l ON l.n = m.wh JOIN vol_variants v ON v.n = m.variant""", company, uom);
            exec("""
                    UPDATE inventory.stock_movements s SET status = 'POSTED', number = 'VOL-' || m.g, posted_at = now()
                      FROM vol_moves m WHERE s.id = m.id AND s.company_id = ?""", company);
            // 2 M ledger rows over the year to date, spread over the variants.
            exec("""
                    INSERT INTO inventory.inventory_transactions (company_id, movement_id, movement_line_id, movement_type,
                        transaction_date, variant_id, warehouse_id, location_id, quantity_base, unit_cost_base, value_base)
                    SELECT ?, m.id, m.line_id, m.type, ?::date + (gs.i % ?), v.id, l.warehouse, l.location, q.qty, 10, q.qty * 10
                      FROM generate_series(0, ? - 1) AS gs(i)
                      JOIN vol_moves m ON m.g = (gs.i / 7) % ?
                      JOIN vol_locations l ON l.n = m.wh
                      JOIN vol_variants v ON v.n = gs.i % ?
                      CROSS JOIN LATERAL (SELECT CASE m.type WHEN 'PURCHASE_RECEIPT' THEN 5 WHEN 'OPENING' THEN 10
                                                             WHEN 'SALES_ISSUE' THEN -4 ELSE -1 END::numeric AS qty) q""", company, yearStart, days, LEDGER_ROWS, MOVEMENTS, VARIANTS);
            // An opening balance per variant and warehouse keeps every balance non-negative.
            exec("""
                    INSERT INTO inventory.inventory_transactions (company_id, movement_id, movement_line_id, movement_type,
                        transaction_date, variant_id, warehouse_id, location_id, quantity_base, unit_cost_base, value_base)
                    SELECT ?, m.id, m.line_id, 'OPENING', ?, v.id, l.warehouse, l.location, 2000, 10, 20000
                      FROM vol_variants v CROSS JOIN vol_locations l
                      CROSS JOIN LATERAL (SELECT id, line_id FROM vol_moves
                                           WHERE type = 'OPENING' AND wh = l.n ORDER BY g LIMIT 1) m""", company, yearStart);
            exec("""
                    INSERT INTO inventory.stock_balances (company_id, variant_id, location_id, warehouse_id, on_hand)
                    SELECT company_id, variant_id, location_id, warehouse_id, sum(quantity_base)
                      FROM inventory.inventory_transactions WHERE company_id = ? GROUP BY 1, 2, 3, 4
                    ON CONFLICT (company_id, variant_id, location_id) DO UPDATE SET on_hand = EXCLUDED.on_hand""", company);
            exec("""
                    INSERT INTO inventory.warehouse_stock (company_id, variant_id, warehouse_id, on_hand)
                    SELECT company_id, variant_id, warehouse_id, sum(quantity_base)
                      FROM inventory.inventory_transactions WHERE company_id = ? GROUP BY 1, 2, 3
                    ON CONFLICT (company_id, variant_id, warehouse_id) DO UPDATE SET on_hand = EXCLUDED.on_hand""", company);
            exec("""
                    INSERT INTO inventory.item_valuations (company_id, variant_id, quantity_base, total_value_base)
                    SELECT company_id, variant_id, sum(quantity_base), sum(value_base)
                      FROM inventory.inventory_transactions WHERE company_id = ? GROUP BY 1, 2
                    ON CONFLICT (company_id, variant_id)
                    DO UPDATE SET quantity_base = EXCLUDED.quantity_base, total_value_base = EXCLUDED.total_value_base""", company);
        }
    }

    private void seedJournal(UUID branch) {
        {
            exec("""
                    CREATE TEMP TABLE vol_accounts ON COMMIT DROP AS
                    SELECT row_number() OVER (ORDER BY code)::int - 1 AS n, id FROM accounting.accounts
                     WHERE company_id = ? AND is_postable AND status = 'ACTIVE'
                       AND (currency_code IS NULL OR currency_code = 'USD')""", company);
            // Only temporary tables are joined: the base tables' statistics do not know the new rows yet.
            exec("""
                    CREATE TEMP TABLE vol_periods ON COMMIT DROP AS
                    SELECT id, start_date, end_date FROM accounting.periods WHERE company_id = ?""", company);
            exec("""
                    CREATE TEMP TABLE vol_entries ON COMMIT DROP AS
                    SELECT d.g, uuidv7() AS id, d.day, p.id AS period
                      FROM (SELECT g, ?::date + (g % ?) AS day FROM generate_series(0, ? - 1) g) d
                      JOIN vol_periods p ON d.day BETWEEN p.start_date AND p.end_date""", yearStart, days, JOURNAL_ENTRIES);
            exec("ANALYZE vol_entries");
            exec("""
                    INSERT INTO accounting.journal_entries (id, company_id, journal_id, entry_date, period_id, entry_type, status,
                                                            description, currency_code, exchange_rate, total_debit, total_credit)
                    SELECT e.id, ?, (SELECT id FROM accounting.journals WHERE company_id = ? ORDER BY code LIMIT 1), e.day,
                           e.period, 'SYSTEM', 'DRAFT', 'Volume entry', 'USD', 1, 500, 500
                      FROM vol_entries e""", company, company);
            exec("""
                    INSERT INTO accounting.journal_lines (company_id, journal_entry_id, line_no, account_id, debit, credit,
                        currency_code, amount_currency, branch_id, entry_date, period_id, is_posted)
                    SELECT ?, e.id, l, a.id,
                           CASE WHEN l <= 5 THEN 100 ELSE 0 END, CASE WHEN l > 5 THEN 100 ELSE 0 END,
                           'USD', CASE WHEN l <= 5 THEN 100 ELSE -100 END, CASE WHEN l % 2 = 0 THEN ?::uuid END,
                           e.day, e.period, true
                      FROM vol_entries e CROSS JOIN generate_series(1, ?) l
                      JOIN vol_accounts a ON a.n = (e.g * 3 + l) % (SELECT count(*) FROM vol_accounts)""", company, branch, JOURNAL_LINES_PER_ENTRY);
        }
    }

    private void seedPartnersAndSales(O2C o, Books b) {
        UUID uom = inv.uom("EA");
        {
            exec("""
                    INSERT INTO partners.partners (company_id, code, name, partner_type)
                    SELECT ?, 'C' || lpad(g::text, 5, '0'), 'Customer ' || g, 'ORGANIZATION' FROM generate_series(1, ?) g
                    UNION ALL
                    SELECT ?, 'S' || lpad(g::text, 5, '0'), 'Supplier ' || g, 'ORGANIZATION' FROM generate_series(1, ?) g""", company, CUSTOMERS, company, SUPPLIERS);
            exec("""
                    INSERT INTO partners.customers (partner_id, company_id, currency_code, payment_terms_id)
                    SELECT id, company_id, 'USD', ? FROM partners.partners WHERE company_id = ? AND code LIKE 'C0%'""", o.terms(), company);
            exec("""
                    INSERT INTO partners.suppliers (partner_id, company_id, currency_code, payment_terms_id)
                    SELECT id, company_id, 'USD', ? FROM partners.partners WHERE company_id = ? AND code LIKE 'S0%'""", o.terms(), company);
            exec("""
                    CREATE TEMP TABLE vol_customers ON COMMIT DROP AS
                    SELECT row_number() OVER (ORDER BY code)::int - 1 AS n, id FROM partners.partners
                     WHERE company_id = ? AND code LIKE 'C0%'""", company);
            exec("""
                    CREATE TEMP TABLE vol_invoices ON COMMIT DROP AS
                    SELECT g, uuidv7() AS id, ?::date + (g % ?) AS day, c.id AS customer
                      FROM generate_series(0, ? - 1) g JOIN vol_customers c ON c.n = g % ?""", yearStart, days, INVOICES, CUSTOMERS);
            exec("ANALYZE vol_invoices");
            exec("""
                    INSERT INTO sales.invoices (id, company_id, document_type, customer_id, invoice_date, accounting_date, due_date,
                        currency_code, exchange_rate, billing_address, status, subtotal, tax_total, total, subtotal_base,
                        tax_total_base, total_base)
                    SELECT id, ?, 'INVOICE', customer, day, day, day + 30, 'USD', 1, '{}'::jsonb, 'DRAFT', 400, 40, 440, 400, 40, 440
                      FROM vol_invoices""", company);
            exec("""
                    INSERT INTO sales.invoice_lines (company_id, invoice_id, line_no, variant_id, description, quantity, uom_id,
                        quantity_base, unit_price, tax_code_id, net_amount, tax_amount, total_amount, net_amount_base,
                        tax_amount_base, branch_id)
                    SELECT ?, i.id, l, v.id, 'Volume line', 4, ?, 4, 25, ?, 100, 10, 110, 100, 10, ?
                      FROM vol_invoices i CROSS JOIN generate_series(1, ?) l
                      JOIN vol_variants v ON v.n = (i.g * 4 + l) % ?""", company, uom, o.taxCode(), o.inv().branch(), INVOICE_LINES, VARIANTS);
            exec("""
                    UPDATE sales.invoices s SET status = 'POSTED', number = 'VOL-' || i.g, posted_at = now()
                      FROM vol_invoices i WHERE s.id = i.id AND s.company_id = ?""", company);
            exec("""
                    INSERT INTO accounting.open_items (company_id, kind, partner_id, account_id, source_module, source_type,
                        source_id, document_number, document_date, due_date, currency_code, original_amount, open_amount,
                        original_amount_base, open_amount_base, exchange_rate, journal_entry_id, status)
                    SELECT ?, 'RECEIVABLE', i.customer, ?, 'sales', 'INVOICE', i.id, 'VOL-' || i.g, i.day, i.day + 30, 'USD',
                           440, CASE WHEN i.g % 3 = 0 THEN 0 ELSE 440 END, 440, CASE WHEN i.g % 3 = 0 THEN 0 ELSE 440 END, 1,
                           (SELECT id FROM vol_entries ORDER BY g LIMIT 1),
                           CASE WHEN i.g % 3 = 0 THEN 'SETTLED' ELSE 'OPEN' END
                      FROM vol_invoices i""", company, b.account("1100"));
            // Open orders: half delivered, nothing invoiced.
            exec("""
                    CREATE TEMP TABLE vol_orders ON COMMIT DROP AS
                    SELECT g, uuidv7() AS id, ?::date + (g % ?) AS day, c.id AS customer
                      FROM generate_series(0, ? - 1) g JOIN vol_customers c ON c.n = g % ?""", yearStart, days, ORDERS, CUSTOMERS);
            exec("ANALYZE vol_orders");
            exec("""
                    INSERT INTO sales.sales_orders (id, company_id, number, customer_id, branch_id, warehouse_id, order_date,
                        currency_code, invoice_policy, shipping_address, billing_address, status, invoice_status, subtotal,
                        tax_total, total)
                    SELECT id, ?, 'VOL-' || g, customer, ?, ?, day, 'USD', 'DELIVERED', '{}'::jsonb, '{}'::jsonb, 'DRAFT',
                           'NOT_INVOICED', 200, 20, 220
                      FROM vol_orders""", company, o.inv().branch(), o.warehouse());
            exec("""
                    INSERT INTO sales.sales_order_lines (company_id, sales_order_id, line_no, variant_id, description, is_stockable,
                        quantity, uom_id, quantity_base, unit_price, tax_code_id, net_amount, tax_amount, total_amount,
                        delivered_quantity_base)
                    SELECT ?, s.id, l, v.id, 'Volume line', true, 4, ?, 4, 25, ?, 100, 10, 110, CASE WHEN s.g % 2 = 0 THEN 2 ELSE 0 END
                      FROM vol_orders s CROSS JOIN generate_series(1, 2) l JOIN vol_variants v ON v.n = (s.g * 2 + l) % ?""", company, uom, o.taxCode(), VARIANTS);
            exec("""
                    UPDATE sales.sales_orders s SET status = 'CONFIRMED', confirmed_at = now(),
                           exchange_rate = 1, credit_check_result = 'PASSED'
                      FROM vol_orders o WHERE s.id = o.id AND s.company_id = ?""", company);
        }
    }

    private void seedProcurement(O2C o, Books b) {
        UUID uom = inv.uom("EA");
        UUID actor = o.inv().user().id();
        {
            exec("""
                    CREATE TEMP TABLE vol_suppliers ON COMMIT DROP AS
                    SELECT row_number() OVER (ORDER BY code)::int - 1 AS n, id FROM partners.partners
                     WHERE company_id = ? AND code LIKE 'S0%'""", company);
            // One order per receipt movement; every other receipt billed.
            exec("""
                    CREATE TEMP TABLE vol_pos ON COMMIT DROP AS
                    SELECT row_number() OVER (ORDER BY m.g)::int - 1 AS g, uuidv7() AS id, uuidv7() AS receipt, uuidv7() AS bill,
                           m.id AS movement, m.day, l.warehouse, l.location
                      FROM vol_moves m JOIN vol_locations l ON l.n = m.wh
                     WHERE m.type = 'PURCHASE_RECEIPT'
                     LIMIT ?""", PURCHASE_ORDERS);
            exec("ALTER TABLE vol_pos ADD COLUMN supplier uuid");
            exec("UPDATE vol_pos p SET supplier = s.id FROM vol_suppliers s WHERE s.n = p.g % ?", SUPPLIERS);
            exec("""
                    CREATE TEMP TABLE vol_po_lines ON COMMIT DROP AS
                    SELECT p.g, p.id AS po, p.receipt, p.bill, p.location, l AS line_no, uuidv7() AS id, uuidv7() AS receipt_line,
                           v.id AS variant
                      FROM vol_pos p CROSS JOIN generate_series(1, 2) l JOIN vol_variants v ON v.n = (p.g * 2 + l) % ?""", VARIANTS);
            exec("ANALYZE vol_pos");
            exec("ANALYZE vol_po_lines");
            exec("""
                    INSERT INTO procurement.purchase_orders (id, company_id, supplier_id, branch_id, warehouse_id, order_date,
                        expected_date, currency_code, status, subtotal, tax_total, total)
                    SELECT id, ?, supplier, ?, warehouse, day, day + 7, 'USD', 'DRAFT', 100, 10, 110 FROM vol_pos""", company, o.inv().branch());
            exec("""
                    INSERT INTO procurement.purchase_order_lines (id, company_id, purchase_order_id, line_no, variant_id, description,
                        is_stockable, quantity, uom_id, quantity_base, unit_price, tax_code_id, net_amount, tax_amount,
                        total_amount, received_quantity_base, billed_quantity_base)
                    SELECT id, ?, po, line_no, variant, 'Volume line', true, 5, ?, 5, 10, ?, 50, 5, 55, 5,
                           CASE WHEN g % 2 = 0 THEN 5 ELSE 0 END
                      FROM vol_po_lines""", company, uom, o.taxCode());
            exec("""
                    UPDATE procurement.purchase_orders po SET status = 'RECEIVED', number = 'VOL-' || p.g,
                           billing_status = CASE WHEN p.g % 2 = 0 THEN 'BILLED' ELSE 'NOT_BILLED' END,
                           approved_by = ?, approved_at = now()
                      FROM vol_pos p WHERE po.id = p.id AND po.company_id = ?""", actor, company);
            exec("""
                    INSERT INTO procurement.goods_receipts (id, company_id, purchase_order_id, supplier_id, branch_id, warehouse_id,
                        receipt_date, status, currency_code, number)
                    SELECT receipt, ?, id, supplier, ?, warehouse, day, 'DRAFT', 'USD', 'GRV-' || g FROM vol_pos""", company, o.inv().branch());
            exec("""
                    INSERT INTO procurement.goods_receipt_lines (id, company_id, goods_receipt_id, line_no, purchase_order_line_id,
                        variant_id, location_id, quantity, uom_id, quantity_base, unit_cost_doc, unit_cost_base, value_base,
                        billed_quantity_base, billed_value_base)
                    SELECT receipt_line, ?, receipt, line_no, id, variant, location, 5, ?, 5, 10, 10, 50,
                           CASE WHEN g % 2 = 0 THEN 5 ELSE 0 END, CASE WHEN g % 2 = 0 THEN 50 ELSE 0 END
                      FROM vol_po_lines""", company, uom);
            exec("""
                    UPDATE procurement.goods_receipts r SET status = 'POSTED', stock_movement_id = p.movement, exchange_rate = 1,
                           posted_at = now()
                      FROM vol_pos p WHERE r.id = p.receipt AND r.company_id = ?""", company);
            exec("""
                    INSERT INTO procurement.supplier_bills (id, company_id, document_type, supplier_invoice_number, supplier_id,
                        purchase_order_id, bill_date, accounting_date, due_date, currency_code, exchange_rate, status, match_status,
                        subtotal, tax_total, total, subtotal_base, tax_total_base, total_base)
                    SELECT bill, ?, 'BILL', 'SUP-' || g, supplier, id, day, day, day + 30, 'USD', 1, 'DRAFT', 'MATCHED',
                           100, 10, 110, 100, 10, 110
                      FROM vol_pos WHERE g % 2 = 0""", company);
            exec("""
                    INSERT INTO procurement.supplier_bill_lines (company_id, supplier_bill_id, line_no, line_kind,
                        purchase_order_line_id, goods_receipt_line_id, variant_id, description, quantity, uom_id, quantity_base,
                        unit_price, tax_code_id, net_amount, tax_amount, total_amount, net_amount_base, tax_amount_base,
                        receipt_value_base, branch_id)
                    SELECT ?, bill, line_no, 'RECEIVED_STOCK', id, receipt_line, variant, 'Volume line',
                           5, ?, 5, 10, ?, 50, 5, 55, 50, 5, 50, ?
                      FROM vol_po_lines WHERE g % 2 = 0""", company, uom, o.taxCode(), o.inv().branch());
            exec("""
                    UPDATE procurement.supplier_bills s SET status = 'POSTED', number = 'VB-' || p.g, posted_at = now()
                      FROM vol_pos p WHERE s.id = p.bill AND s.company_id = ?""", company);
            exec("""
                    INSERT INTO accounting.open_items (company_id, kind, partner_id, account_id, source_module, source_type,
                        source_id, document_number, document_date, due_date, currency_code, original_amount, open_amount,
                        original_amount_base, open_amount_base, exchange_rate, journal_entry_id, status)
                    SELECT ?, 'PAYABLE', supplier, ?, 'procurement', 'BILL', bill, 'VB-' || g, day, day + 30, 'USD',
                           110, 110, 110, 110, 1, (SELECT id FROM vol_entries ORDER BY g LIMIT 1), 'OPEN'
                      FROM vol_pos WHERE g % 2 = 0""", company, b.account("2000"));
        }
    }

    private void seedHr(UUID branch, UUID approver) {
        LocalDate hired = yearStart.minusYears(2);
        {
            exec(
                    "INSERT INTO org.departments (company_id, code, name) VALUES (?, 'VOLOPS', 'Volume operations')",
                    company);
            exec("""
                    INSERT INTO hr.positions (company_id, code, title, department_id)
                    SELECT company_id, 'VOLDEV', 'Developer', id FROM org.departments WHERE company_id = ? AND code = 'VOLOPS'""", company);
            exec("""
                    INSERT INTO hr.employees (company_id, employee_number, first_name, last_name, hire_date, status)
                    SELECT ?, 'V' || lpad(g::text, 5, '0'), 'First', 'Last' || g, ?, 'ACTIVE' FROM generate_series(1, ?) g""", company, hired, EMPLOYEES);
            exec("""
                    INSERT INTO hr.employment_assignments (company_id, employee_id, branch_id, department_id, position_id,
                        effective_from)
                    SELECT e.company_id, e.id, ?, p.department_id, p.id, e.hire_date
                      FROM hr.employees e JOIN hr.positions p ON p.company_id = e.company_id AND p.code = 'VOLDEV'
                     WHERE e.company_id = ? AND e.employee_number LIKE 'V%'""", branch, company);
            exec("""
                    INSERT INTO hr.attendance_records (company_id, employee_id, work_date, status, worked_minutes, source)
                    SELECT e.company_id, e.id, d::date, CASE WHEN (extract(day FROM d)::int + length(e.employee_number)) % 10 = 0
                                                             THEN 'ABSENT' ELSE 'PRESENT' END,
                           480, 'MANUAL'
                      FROM hr.employees e CROSS JOIN generate_series(?::date - ?, ?::date - 1, interval '1 day') d
                     WHERE e.company_id = ? AND e.employee_number LIKE 'V%'""", yearStart.plusDays(days - 1), ATTENDANCE_DAYS, yearStart.plusDays(days - 1), company);
            exec("""
                    INSERT INTO hr.leave_types (company_id, code, name, annual_entitlement_days, accrual_method)
                    VALUES (?, 'VOL', 'Volume leave', 20, 'ANNUAL')""", company);
            exec("""
                    INSERT INTO hr.leave_requests (company_id, employee_id, leave_type_id, start_date, end_date, days, status,
                        submitted_at, decided_by, decided_at)
                    SELECT e.company_id, e.id, t.id, ?::date + (row_number() OVER (ORDER BY e.id))::int % ?,
                           ?::date + (row_number() OVER (ORDER BY e.id))::int % ?, 1, 'APPROVED', now(), ?, now()
                      FROM hr.employees e JOIN hr.leave_types t ON t.company_id = e.company_id AND t.code = 'VOL'
                     WHERE e.company_id = ? AND e.employee_number LIKE 'V%'""", yearStart, days, yearStart, days, approver, company);
        }
    }

    /** Statistics for the planner after the bulk load (only the owner or a superuser may analyze). */
    private static void analyze() throws java.sql.SQLException {
        try (java.sql.Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                java.sql.Statement st = c.createStatement()) {
            st.execute("ANALYZE");
        }
    }

    /** Literals inlined: CREATE TABLE AS takes no bind parameters. */
    private void exec(String sql, Object... bindings) {
        seed.execute(seed.query(sql, bindings).getSQL(org.jooq.conf.ParamType.INLINED));
    }

    /** Runs {@code work} in one transaction of a superuser session without row triggers (bulk loading). */
    private void bulkLoad(Runnable work) throws java.sql.SQLException {
        try (java.sql.Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE)) {
            c.setAutoCommit(false);
            seed = org.jooq.impl.DSL.using(c, org.jooq.SQLDialect.POSTGRES);
            seed.execute("SET LOCAL session_replication_role = replica");
            work.run();
            c.commit();
        } finally {
            seed = null;
        }
    }

    /** Runs {@code work} in one transaction of a new erp_app session bound to the company. */
    private void seeding(Runnable work) throws java.sql.SQLException {
        try (java.sql.Connection c = TestDatabase.connectAs("erp_app", TestDatabase.APP_PASSWORD)) {
            c.setAutoCommit(false);
            seed = org.jooq.impl.DSL.using(c, org.jooq.SQLDialect.POSTGRES);
            seed.execute("SELECT set_config('app.company_id', ?, true)", company.toString());
            work.run();
            c.commit();
        } finally {
            seed = null;
        }
    }

    private <T> T inCompany(java.util.function.Supplier<T> work) {
        return CurrentContext.callWith(
                RequestContext.forRequest("volume-" + UUID.randomUUID()).withCompany(company),
                () -> tx.execute(s -> work.get()));
    }
}
