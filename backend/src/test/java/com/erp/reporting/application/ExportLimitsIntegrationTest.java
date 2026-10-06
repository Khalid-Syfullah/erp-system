package com.erp.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.platform.files.FileService;
import com.erp.reporting.persistence.ExportJobRepository;
import com.erp.reporting.persistence.ViewReports;
import com.erp.support.IntegrationTest;
import com.erp.support.ReportingFixtures;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * The export limits (DEVELOPMENT_PLAN.md Phase 10): too many rows for the format, or a file above
 * the size limit, fail the job with an error code instead of producing a truncated file.
 */
class ExportLimitsIntegrationTest extends IntegrationTest {

    @Autowired
    SalesFixtures sales;

    @Autowired
    ReportingFixtures rep;

    @Autowired
    ExportJobRepository jobs;

    @Autowired
    ReportCatalog catalog;

    @Autowired
    ReportParameterParser parser;

    @Autowired
    ViewReports views;

    @Autowired
    FinancialReportRows financial;

    @Autowired
    FileService files;

    @Autowired
    ReportingContext context;

    @Autowired
    ReportingProperties properties;

    @Autowired
    TransactionTemplate tx;

    @Test
    void exportsAboveTheRowOrSizeLimitFail() throws Exception {
        O2C o = sales.setup();
        UUID company = o.inv().company();
        sales.stock(o, "10", "1");
        // Two open order lines.
        sales.confirmedOrder(o, sales.line(o.variant(), "1", null));
        sales.confirmedOrder(o, sales.line(o.variant(), "2", null));
        Cookie analyst = rep.user(company, "reporting.sales.read", "reporting.export.create");
        String today = o.inv().today().toString();
        Map<String, String> backlog = Map.of();
        Map<String, String> period = Map.of("from", today, "to", today);

        UUID csv = rep.export(analyst, company, "order-backlog", "CSV", backlog);
        limited(1, 10, DataSize.ofMegabytes(1)).processQueued(company);
        assertJob(analyst, company, csv, "FAILED", "TOO_MANY_ROWS");

        UUID pdf = rep.export(analyst, company, "order-backlog", "PDF", backlog);
        limited(100, 1, DataSize.ofMegabytes(1)).processQueued(company);
        assertJob(analyst, company, pdf, "FAILED", "TOO_MANY_ROWS");

        UUID big = rep.export(analyst, company, "sales-summary", "XLSX", period);
        limited(100, 100, DataSize.ofBytes(10)).processQueued(company);
        assertJob(analyst, company, big, "FAILED", "FILE_TOO_LARGE");

        UUID fine = rep.export(analyst, company, "order-backlog", "PDF", backlog);
        limited(100, 100, DataSize.ofMegabytes(1)).processQueued(company);
        assertJob(analyst, company, fine, "SUCCEEDED", null);
    }

    private void assertJob(Cookie session, UUID company, UUID job, String status, String error) throws Exception {
        String body = rep.job(session, company, job);
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo(status);
        assertThat((String) JsonPath.read(body, "$.errorCode")).isEqualTo(error);
    }

    private ExportJobs limited(long rows, long pdfRows, DataSize size) {
        ReportingProperties limits = new ReportingProperties(
                properties.datasource(),
                properties.queryTimeout(),
                Duration.ofMinutes(1),
                properties.defaultPageSize(),
                properties.maxPageSize(),
                rows,
                pdfRows,
                size,
                properties.exportRetention(),
                properties.exportsPerUserPerHour(),
                properties.reportsPerUserPerMinute());
        return new ExportJobs(jobs, catalog, parser, views, financial, files, context, limits, tx);
    }
}
