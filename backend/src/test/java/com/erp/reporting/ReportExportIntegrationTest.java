package com.erp.reporting;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.ProcurementFixtures.id;
import static com.erp.support.ReportingFixtures.csv;
import static com.erp.support.ReportingFixtures.decimal;
import static com.erp.support.ReportingFixtures.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.reporting.application.ExportJobs;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.AuthTestSupport;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.ReportingFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asynchronous exports (API.md §17.11, ADR-040): the files hold exactly the report's rows, CSV is
 * safe against formula injection, XLSX and PDF are readable with totals; requests are idempotent,
 * permission-checked, rate-limited and owned by the requester; the worker applies the requester's
 * branch scope; files expire.
 */
class ReportExportIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SalesFixtures sales;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    ReportingFixtures rep;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    ExportJobs jobs;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private O2C o;
    private UUID company;
    private Cookie analyst;
    private LocalDate today;
    private Map<String, String> period;

    @BeforeEach
    void setUp() throws Exception {
        o = sales.setup();
        company = o.inv().company();
        today = o.inv().today();
        period = Map.of("from", today.toString(), "to", today.toString());
        sales.stock(o, "100", "10");
        UUID evil = customer("EVIL", "=HYPERLINK(\"http://evil.test\",\"x\")");
        invoice(o.customer(), "4");
        invoice(evil, "2");
        analyst = rep.user(company, "reporting.sales.read", "reporting.export.create");
    }

    @Test
    void aCsvExportHoldsTheReportsRowsAndNeutralisesFormulas() throws Exception {
        MvcResult requested = rep.requestExport(analyst, company, "sales-by-customer", "CSV", period, "csv-export-1")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.contentPath").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        UUID job = UUID.fromString(JsonPath.read(requested.getResponse().getContentAsString(), "$.id"));
        assertThat(requested.getResponse().getHeader("Location")).endsWith("/report-exports/" + job);
        // The same request again is the same job.
        rep.requestExport(analyst, company, "sales-by-customer", "CSV", period, "csv-export-1")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(job.toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                ReportingFixtures.path(company, "/report-exports/" + job + "/content"))
                        .cookie(analyst))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_READY"));

        assertThat(rep.processExports(company)).isEqualTo(1);
        String done = rep.job(analyst, company, job);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("SUCCEEDED");
        assertThat((Integer) JsonPath.read(done, "$.rowCount")).isEqualTo(2);
        assertThat((String) JsonPath.read(done, "$.expiresAt")).isNotNull();
        MvcResult file = rep.content(analyst, company, job);
        assertThat(file.getResponse().getContentType()).startsWith("text/csv");
        assertThat(file.getResponse().getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("sales-by-customer-" + today + ".csv");

        List<List<String>> lines = csv(file.getResponse().getContentAsByteArray());
        assertThat(lines.getFirst())
                .containsExactly("Customer ID", "Customer code", "Customer", "Documents", "Net sales", "Tax", "Gross");
        List<Map<String, Object>> report =
                rows(rep.run(analyst, company, "sales-by-customer", "from=" + today + "&to=" + today));
        assertThat(lines).hasSize(report.size() + 1);
        for (int i = 0; i < report.size(); i++) {
            assertThat(lines.get(i + 1).get(1)).isEqualTo(report.get(i).get("customerCode"));
            assertThat(decimal(lines.get(i + 1).get(4)))
                    .isEqualByComparingTo(decimal(report.get(i).get("netSalesBase")));
        }
        // A spreadsheet must not evaluate a customer's name.
        assertThat(lines).anySatisfy(l -> assertThat(l.get(2)).isEqualTo("'=HYPERLINK(\"http://evil.test\",\"x\")"));
    }

    @Test
    void xlsxAndPdfExportsAreReadableWithTotals() throws Exception {
        byte[] xlsx = rep.exported(analyst, company, "sales-by-customer", "XLSX", period);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            List<Row> sheet = workbook.getFirstSheet().read();
            assertThat(sheet.getFirst().getCellText(0)).isEqualTo("Sales by customer");
            int header = 0;
            while (!"Customer ID".equals(sheet.get(header).getCellText(0))) {
                header++;
            }
            assertThat(sheet.get(header).getCellText(4)).isEqualTo("Net sales");
            assertThat(sheet.get(header + 1).getCellAsNumber(4))
                    .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("100"));
            Row total = sheet.getLast();
            assertThat(total.getCellText(0)).isEqualTo("Total");
            assertThat(total.getCellAsNumber(4))
                    .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("150"));
            // XLSX cells are typed: the name is text, not a formula.
            assertThat(sheet.get(header + 2).getCellText(2)).startsWith("=HYPERLINK");
            assertThat(sheet.get(header + 2).getCell(2).getFormula()).isNull();
        }

        byte[] pdf = rep.exported(analyst, company, "sales-by-customer", "PDF", period);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(text)
                .contains("Sales by customer")
                .contains("CUST1")
                .contains("Total")
                .contains("150");
    }

    @Test
    void accountingStatementsExportFromAccountingsFigures() throws Exception {
        Books b = acc.books(company);
        Cookie accountant = rep.user(company, "accounting.report.read", "reporting.export.create");
        byte[] file = rep.exported(accountant, company, "trial-balance", "CSV", period);
        List<List<String>> lines = csv(file);
        String trialBalance = acc.body(b, "/reports/trial-balance?from=" + today + "&to=" + today);
        List<String> codes = JsonPath.read(trialBalance, "$.rows[*].account.code");
        assertThat(lines).hasSize(codes.size() + 1);
        assertThat(lines.stream().skip(1).map(l -> l.getFirst()).toList()).isEqualTo(codes);
        BigDecimal debit =
                lines.stream().skip(1).map(l -> new BigDecimal(l.get(4))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).isEqualByComparingTo(decimal(JsonPath.read(trialBalance, "$.totalDebit")));

        byte[] ageing = rep.exported(
                rep.user(company, "accounting.report.read", "accounting.ar.read", "reporting.export.create"),
                company,
                "ar-ageing",
                "XLSX",
                Map.of());
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(ageing))) {
            Row total = workbook.getFirstSheet().read().getLast();
            assertThat(total.getCellAsNumber(7))
                    .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("165"));
        }
    }

    @Test
    void everyFinancialStatementExportsAccountingsFigures() throws Exception {
        Books b = acc.books(company);
        acc.posted(b, today, Map.of("1010", "300", "3000", "-300"));
        acc.posted(b, today, Map.of("6000", "70", "1010", "-70"));
        Cookie accountant =
                rep.user(company, "accounting.report.read", "accounting.ap.read", "reporting.export.create");
        String range = "from=" + today + "&to=" + today;

        List<List<String>> pl = csv(rep.exported(accountant, company, "profit-and-loss", "CSV", period));
        assertThat(pl.getLast()).startsWith("NET", "", "Net profit");
        assertThat(decimal(pl.getLast().get(3)))
                .isEqualByComparingTo(
                        decimal(JsonPath.read(acc.body(b, "/reports/profit-and-loss?" + range), "$.netProfit")));

        List<List<String>> bs = csv(rep.exported(accountant, company, "balance-sheet", "CSV", Map.of()));
        String sheet = acc.body(b, "/reports/balance-sheet?asOf=" + today);
        assertThat(bs.stream()
                        .filter(l -> l.get(2).equals("Total assets"))
                        .findFirst()
                        .orElseThrow()
                        .get(3))
                .satisfies(v ->
                        assertThat(decimal(v)).isEqualByComparingTo(decimal(JsonPath.read(sheet, "$.totalAssets"))));

        Map<String, String> ledger =
                Map.of("accountId", b.account("1010").toString(), "from", today.toString(), "to", today.toString());
        List<List<String>> gl = csv(rep.exported(accountant, company, "general-ledger", "CSV", ledger));
        assertThat(gl.get(1).get(2)).isEqualTo("Opening balance");
        assertThat(decimal(gl.getLast().get(7)))
                .isEqualByComparingTo(decimal(JsonPath.read(
                        acc.body(b, "/reports/general-ledger?accountId=" + b.account("1010") + "&" + range),
                        "$.closing")));

        Map<String, String> book =
                Map.of("bankAccountId", b.bankAccount().toString(), "from", today.toString(), "to", today.toString());
        List<List<String>> cash = csv(rep.exported(accountant, company, "cash-book", "CSV", book));
        assertThat(decimal(cash.getLast().get(8)))
                .isEqualByComparingTo(decimal(JsonPath.read(
                        acc.body(b, "/reports/cash-book?bankAccountId=" + b.bankAccount() + "&" + range),
                        "$.closing")));

        // No payables: the header only.
        assertThat(csv(rep.exported(accountant, company, "ap-ageing", "CSV", Map.of())))
                .hasSize(1);
        // An export of a statement whose parameters Accounting rejects fails cleanly.
        UUID unknown = rep.export(
                accountant,
                company,
                "general-ledger",
                "CSV",
                Map.of("accountId", UUID.randomUUID().toString(), "from", today.toString(), "to", today.toString()));
        rep.processExports(company);
        assertThat((String) JsonPath.read(rep.job(accountant, company, unknown), "$.errorCode"))
                .isEqualTo("INVALID_PARAMETERS");
    }

    @Test
    void exportsAreCheckedOwnedAndScoped() throws Exception {
        // Without the export permission, or without the report's permission.
        Cookie reader = rep.user(company, "reporting.sales.read");
        rep.requestExport(reader, company, "sales-summary", "CSV", period, "no-export-perm")
                .andExpect(status().isForbidden());
        Cookie exporter = rep.user(company, "reporting.export.create");
        rep.requestExport(exporter, company, "sales-summary", "CSV", period, "no-report-perm")
                .andExpect(status().isForbidden());
        rep.requestExport(analyst, company, "no-such-report", "CSV", period, "no-such-report")
                .andExpect(status().isNotFound());
        rep.requestExport(
                        analyst,
                        company,
                        "sales-summary",
                        "CSV",
                        Map.of("to", today.toString(), "bogus", "1"),
                        "bad-parameters")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].pointer")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("/parameters/from", "/parameters/bogus")));
        rep.requestExport(analyst, company, "sales-summary", "DOCX", period, "bad-format")
                .andExpect(status().isUnprocessableContent());
        rep.requestExport(analyst, company, "sales-summary", "CSV", period, null)
                .andExpect(status().isBadRequest());

        // Only the requester sees the job; another company's member not even the company.
        UUID job = rep.export(analyst, company, "sales-summary", "CSV", period);
        Cookie colleague = rep.user(company, "reporting.sales.read", "reporting.export.create");
        assertThat(rep.content(colleague, company, job).getResponse().getStatus())
                .isEqualTo(404);
        Cookie foreign = rep.user(auth.company(), "reporting.sales.read", "reporting.export.create");
        assertThat(rep.content(foreign, company, job).getResponse().getStatus()).isEqualTo(404);
        String mine = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                ReportingFixtures.path(company, "/report-exports"))
                        .cookie(analyst))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(mine, "$.data[*].id")).containsExactly(job.toString());

        // The worker applies the branch scope the requester had.
        UUID east = auth.branch(company, "EAST");
        Cookie eastOnly = rep.user(company, new UUID[] {east}, "reporting.sales.read", "reporting.export.create");
        List<List<String>> scoped = csv(rep.exported(eastOnly, company, "sales-summary", "CSV", period));
        assertThat(scoped.get(1).get(0)).isEqualTo("0");
        List<List<String>> full = csv(rep.exported(analyst, company, "sales-summary", "CSV", period));
        assertThat(full.get(1).get(0)).isEqualTo("2");
    }

    @Test
    void exportsAreRateLimitedAndExpire() throws Exception {
        Cookie busy = rep.user(company, "reporting.sales.read", "reporting.export.create");
        UUID first = null;
        for (int i = 0; i < 10; i++) {
            UUID job = rep.export(busy, company, "sales-summary", "CSV", period);
            first = first == null ? job : first;
        }
        rep.requestExport(busy, company, "sales-summary", "CSV", period, "one-too-many")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        rep.processExports(company);
        assertThat(rep.content(busy, company, first).getResponse().getStatus()).isEqualTo(200);

        UUID expired = first;
        inCompany(() -> dsl.execute(
                "UPDATE reporting.export_jobs SET expires_at = now() - interval '1 minute' WHERE id = ?", expired));
        assertThat(rep.content(busy, company, expired).getResponse().getStatus())
                .isEqualTo(410);
        UUID file = inCompany(() ->
                dsl.fetchValue("SELECT file_id FROM reporting.export_jobs WHERE id = ?", expired) instanceof UUID id
                        ? id
                        : null);
        assertThat(jobs.expire(company)).isEqualTo(1);
        assertThat((String) JsonPath.read(rep.job(busy, company, expired), "$.status"))
                .isEqualTo("EXPIRED");
        assertThat(inCompany(
                        () -> dsl.fetchCount(dsl.selectFrom("platform.files").where("id = ?", file))))
                .isZero();
        assertThat(rep.content(busy, company, expired).getResponse().getStatus())
                .isEqualTo(410);

        // A job whose worker died is failed, and its content is not served.
        UUID stuck = rep.export(analyst, company, "sales-summary", "CSV", period);
        inCompany(() -> dsl.execute(
                "UPDATE reporting.export_jobs SET status = 'RUNNING', started_at = now() - interval '1 day' WHERE id = ?",
                stuck));
        rep.processExports(company);
        assertThat((String) JsonPath.read(rep.job(analyst, company, stuck), "$.errorCode"))
                .isEqualTo("INTERRUPTED");
        MvcResult failed = rep.content(analyst, company, stuck);
        assertThat(failed.getResponse().getStatus()).isEqualTo(409);
        assertThat(failed.getResponse().getContentAsString()).contains("EXPORT_FAILED");
    }

    private <T> T inCompany(java.util.function.Supplier<T> work) {
        return CurrentContext.callWith(
                RequestContext.forRequest("test-" + UUID.randomUUID()).withCompany(company),
                () -> tx.execute(s -> work.get()));
    }

    private UUID customer(String code, String name) throws Exception {
        UUID partner = id(
                sales.create(
                        o, "/partners", OrgFixtures.map("code", code, "name", name, "partnerType", "ORGANIZATION")),
                201);
        mvc.perform(unsafe(put(o.path("/partners/" + partner + "/customer-profile")))
                        .cookie(o.session())
                        .header("If-Match", OrgFixtures.etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "currencyCode", "USD", "paymentTermsId", o.terms(), "defaultTaxCodeId", o.taxCode())))
                .andExpect(status().isOk());
        return partner;
    }

    private void invoice(UUID customer, String quantity) throws Exception {
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
                                List.of(sales.line(o.variant(), quantity, null)))),
                201);
        mvc.perform(unsafe(post(o.path("/sales-orders/" + order + "/confirm")))
                        .cookie(o.session())
                        .header("If-Match", OrgFixtures.etag(0))
                        .header("Idempotency-Key", "confirm-" + order))
                .andExpect(status().isOk());
        sales.postedDelivery(o, order, null);
        sales.postedInvoice(o, order);
    }
}
