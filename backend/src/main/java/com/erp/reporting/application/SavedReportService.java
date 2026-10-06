package com.erp.reporting.application;

import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.reporting.ReportingPermissions;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.persistence.SavedReportRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Saved report parameters (API.md §17.11): private to their owner, or shared with the company by an
 * owner holding {@code reporting.saved_report.share}. A saved report is visible only to users who may
 * run its report; only the owner changes or deletes it. The parameters are validated against the
 * report and stored as given, so relative defaults (the business date) stay relative.
 */
@Service
public class SavedReportService {

    private static final Set<String> PATCHABLE = Set.of("name", "parameters", "isShared");

    /** A saved report to create. */
    public record Command(String reportCode, String name, Map<String, String> parameters, boolean shared) {}

    private final SavedReportRepository saved;
    private final ReportRunner reports;
    private final ReportParameterParser parser;
    private final ReportingContext context;
    private final AuditPort audit;

    SavedReportService(
            SavedReportRepository saved,
            ReportRunner reports,
            ReportParameterParser parser,
            ReportingContext context,
            AuditPort audit) {
        this.saved = saved;
        this.reports = reports;
        this.parser = parser;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportingViews.SavedReport> list(ListQuery query) {
        List<String> visible =
                reports.catalogue().stream().map(ReportDefinition::code).toList();
        return saved.list(context.companyId(), context.actor(), visible, query);
    }

    @Transactional(readOnly = true)
    public ReportingViews.SavedReport get(UUID id) {
        return visible(saved.find(context.companyId(), id).orElseThrow(ApiException::notFound));
    }

    @Transactional
    public ReportingViews.SavedReport create(Command command) {
        validate(command);
        UUID id = saved.insert(
                context.companyId(),
                context.actor(),
                command.reportCode(),
                command.name().strip(),
                command.parameters(),
                command.shared());
        audit.record(AuditEvent.builder("CREATE", "reporting")
                .entity("saved_report", id, command.name().strip())
                .detail("reportCode", command.reportCode())
                .detail("shared", command.shared())
                .build());
        return get(id);
    }

    @Transactional
    public ReportingViews.SavedReport patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        ReportingViews.SavedReport current = owned(id);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 100);
        var parameters = patch.stringMap("parameters", 30);
        var shared = patch.bool("isShared");
        patch.throwIfInvalid();
        Command next = new Command(
                current.reportCode(),
                java.util.Objects.requireNonNull(name.orElse(current.name())),
                java.util.Objects.requireNonNull(parameters.orElse(current.parameters())),
                Boolean.TRUE.equals(shared.orElse(current.shared())));
        validate(next);
        if (!saved.update(
                context.companyId(),
                id,
                current.version(),
                context.actor(),
                next.name().strip(),
                next.parameters(),
                next.shared())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The saved report was modified concurrently.");
        }
        ReportingViews.SavedReport after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "reporting")
                .entity("saved_report", id, after.name())
                .change("name", current.name(), after.name())
                .change("parameters", current.parameters(), after.parameters())
                .change("isShared", current.shared(), after.shared())
                .build());
        return after;
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        ReportingViews.SavedReport current = owned(id);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!saved.delete(context.companyId(), id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The saved report was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "reporting")
                .entity("saved_report", id, current.name())
                .detail("reportCode", current.reportCode())
                .build());
    }

    private void validate(Command command) {
        ReportDefinition definition = reports.authorize(command.reportCode());
        if (command.shared() && !context.isGranted(ReportingPermissions.SAVED_REPORT_SHARE)) {
            throw new ApiException(
                    PlatformErrorCode.FORBIDDEN,
                    "Sharing a saved report requires the permission " + ReportingPermissions.SAVED_REPORT_SHARE + ".");
        }
        parser.parseBody(definition, command.parameters(), context.today(context.companyId()), "/parameters");
    }

    /** Visible: own, or shared — and the caller may run the report. Otherwise 404. */
    private ReportingViews.SavedReport visible(ReportingViews.SavedReport report) {
        boolean readable = report.ownerId().equals(context.actor()) || report.shared();
        if (!readable || reports.catalogue().stream().noneMatch(d -> d.code().equals(report.reportCode()))) {
            throw ApiException.notFound();
        }
        return report;
    }

    /** Locked for a change by its owner; 404 for everyone else (others' reports are not theirs to see). */
    private ReportingViews.SavedReport owned(UUID id) {
        ReportingViews.SavedReport report =
                saved.lockForChange(context.companyId(), id).orElseThrow(ApiException::notFound);
        visible(report);
        if (!report.ownerId().equals(context.actor())) {
            throw new ApiException(PlatformErrorCode.FORBIDDEN, "Only the owner can change a saved report.");
        }
        return report;
    }
}
