package com.erp.reporting.web;

import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.paging.PageResponse;
import com.erp.reporting.application.ReportRunner;
import com.erp.reporting.application.ReportingViews;
import com.erp.reporting.domain.ExportFormat;
import com.erp.reporting.domain.ReportColumn;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportParameter;
import com.erp.reporting.domain.ReportSort;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Response bodies of the Reporting endpoints (API.md §17.11). */
final class ReportingResponses {

    private ReportingResponses() {}

    record Parameter(
            String name,
            String type,
            boolean required,
            @Nullable String defaultValue,
            List<String> values,
            @Nullable Integer min,
            @Nullable Integer max,
            String description) {

        static Parameter of(ReportParameter p) {
            boolean integer = p.type() == com.erp.reporting.domain.ParameterType.INTEGER;
            return new Parameter(
                    p.name(),
                    p.type().name(),
                    p.required(),
                    p.defaultValue(),
                    p.values(),
                    integer ? p.min() : null,
                    integer ? p.max() : null,
                    p.description());
        }
    }

    record Column(String key, String label, String type, boolean total, boolean sortable) {

        static Column of(ReportColumn c) {
            return new Column(c.key(), c.label(), c.type().name(), c.total(), c.sortable());
        }
    }

    /** A catalogue entry: what the report shows, its parameters and where to run and export it. */
    record Report(
            String code,
            String name,
            String module,
            String description,
            List<String> permissions,
            String path,
            String exportPath,
            List<Parameter> parameters,
            List<Column> columns,
            List<String> defaultSort,
            List<String> exportFormats) {

        static Report of(UUID companyId, ReportDefinition d) {
            String path = ApiPaths.V1 + "/companies/" + companyId + "/reports/" + d.code();
            return new Report(
                    d.code(),
                    d.name(),
                    d.module(),
                    d.description(),
                    d.permissions(),
                    path,
                    path + "/exports",
                    d.parameters().stream().map(Parameter::of).toList(),
                    d.columns().stream().map(Column::of).toList(),
                    d.defaultSort().stream().map(ReportSort::canonical).toList(),
                    Arrays.stream(ExportFormat.values()).map(Enum::name).toList());
        }
    }

    record ReportInfo(String code, String name, String module) {}

    record ReportRun(
            ReportInfo report,
            Map<String, String> parameters,
            List<Column> columns,
            List<Map<String, @Nullable Object>> data,
            @Nullable Map<String, Object> totals,
            PageResponse.Page page,
            OffsetDateTime generatedAt) {

        static ReportRun of(ReportRunner.ReportResult r) {
            ReportDefinition d = r.definition();
            return new ReportRun(
                    new ReportInfo(d.code(), d.name(), d.module()),
                    r.parameters(),
                    d.columns().stream().map(Column::of).toList(),
                    r.rows(),
                    r.totals(),
                    r.page(),
                    r.generatedAt());
        }
    }

    record ExportJob(
            UUID id,
            String reportCode,
            Map<String, String> parameters,
            String format,
            String status,
            @Nullable Long rowCount,
            @Nullable String errorCode,
            OffsetDateTime requestedAt,
            @Nullable OffsetDateTime startedAt,
            @Nullable OffsetDateTime completedAt,
            @Nullable OffsetDateTime expiresAt,
            @Nullable String contentPath) {

        static ExportJob of(UUID companyId, ReportingViews.ExportJob j) {
            return new ExportJob(
                    j.id(),
                    j.reportCode(),
                    j.parameters(),
                    j.format(),
                    j.status(),
                    j.rowCount(),
                    j.errorCode(),
                    j.requestedAt(),
                    j.startedAt(),
                    j.completedAt(),
                    j.expiresAt(),
                    "SUCCEEDED".equals(j.status()) ? path(companyId, j.id()) + "/content" : null);
        }

        static String path(UUID companyId, UUID id) {
            return ApiPaths.V1 + "/companies/" + companyId + "/report-exports/" + id;
        }
    }

    record SavedReport(
            UUID id,
            String reportCode,
            String name,
            Map<String, String> parameters,
            boolean isShared,
            UUID ownerId,
            boolean owned,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static SavedReport of(ReportingViews.SavedReport s, UUID caller) {
            return new SavedReport(
                    s.id(),
                    s.reportCode(),
                    s.name(),
                    s.parameters(),
                    s.shared(),
                    s.ownerId(),
                    s.ownerId().equals(caller),
                    s.createdAt(),
                    s.updatedAt(),
                    s.version());
        }
    }
}
