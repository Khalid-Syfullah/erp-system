package com.erp.reporting.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Reporting settings under {@code erp.reporting} (ADR-040, SECURITY.md §9).
 *
 * @param datasource the read-only {@code erp_reporting} connection pool; the URL defaults to the
 *     application database and may point at a read replica
 * @param queryTimeout statement timeout of synchronous reports and dashboards
 * @param exportTimeout statement timeout of an export's query
 * @param maxExportRows rows above which a CSV or XLSX export fails ({@code TOO_MANY_ROWS})
 * @param maxPdfRows the row limit of PDF exports (a PDF is for reading, not for data transfer)
 * @param maxExportSize file size above which an export fails ({@code FILE_TOO_LARGE})
 * @param exportRetention how long an export file can be downloaded
 * @param exportsPerUserPerHour export requests per user and hour (SECURITY.md §9)
 * @param reportsPerUserPerMinute synchronous report requests per user and minute, per instance
 */
@Validated
@ConfigurationProperties(prefix = "erp.reporting")
public record ReportingProperties(
        @Valid @NotNull @DefaultValue Datasource datasource,
        @NotNull @DefaultValue("15s") Duration queryTimeout,
        @NotNull @DefaultValue("10m") Duration exportTimeout,
        @Min(1) @Max(1000) @DefaultValue("100") int defaultPageSize,
        @Min(1) @Max(5000) @DefaultValue("1000") int maxPageSize,
        @Min(1) @DefaultValue("1000000") long maxExportRows,
        @Min(1) @DefaultValue("10000") long maxPdfRows,
        @NotNull @DefaultValue("200MB") DataSize maxExportSize,
        @NotNull @DefaultValue("7d") Duration exportRetention,
        @Min(1) @Max(10000) @DefaultValue("10") int exportsPerUserPerHour,
        @Min(1) @Max(100000) @DefaultValue("30") int reportsPerUserPerMinute) {

    /**
     * @param url JDBC URL; empty means {@code spring.datasource.url}
     * @param password required in production ({@code ERP_DB_REPORTING_PASSWORD})
     */
    public record Datasource(
            @Nullable String url,
            @NotNull @DefaultValue("erp_reporting") String username,
            @Nullable String password,
            @Min(1) @Max(50) @DefaultValue("5") int poolSize) {}
}
