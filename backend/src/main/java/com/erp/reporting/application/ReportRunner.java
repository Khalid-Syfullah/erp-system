package com.erp.reporting.application;

import com.erp.platform.config.ErpProperties;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.security.FixedWindowRateLimiter;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.CursorCodec;
import com.erp.platform.web.paging.PageResponse;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportParameters;
import com.erp.reporting.domain.ReportSort;
import com.erp.reporting.domain.ReportSourceKind;
import com.erp.reporting.persistence.ViewReports;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * Runs catalogued reports synchronously (API.md §17.11): the caller must hold every permission of
 * the report; parameters are validated; view-backed reports are keyset-paginated with signed cursors
 * and return their totals with the first page. Accounting's statements are served by Accounting at
 * the same paths and only exported through Reporting. Requests are rate-limited per user (SECURITY.md
 * §9).
 */
@Service
public class ReportRunner {

    /** A page of a report run. */
    public record ReportResult(
            ReportDefinition definition,
            Map<String, String> parameters,
            List<Map<String, @Nullable Object>> rows,
            @Nullable Map<String, Object> totals,
            PageResponse.Page page,
            OffsetDateTime generatedAt) {}

    private final ReportCatalog catalog;
    private final ReportParameterParser parser;
    private final ViewReports views;
    private final ReportingContext context;
    private final ReportingProperties properties;
    private final ErpProperties erp;
    private final CursorCodec cursors;
    private final FixedWindowRateLimiter limiter;

    ReportRunner(
            ReportCatalog catalog,
            ReportParameterParser parser,
            ViewReports views,
            ReportingContext context,
            ReportingProperties properties,
            ErpProperties erp,
            CursorCodec cursors,
            Clock clock) {
        this.catalog = catalog;
        this.parser = parser;
        this.views = views;
        this.context = context;
        this.properties = properties;
        this.erp = erp;
        this.cursors = cursors;
        this.limiter = new FixedWindowRateLimiter(clock);
    }

    /** The reports the caller may run (GET {c}/reports). */
    public List<ReportDefinition> catalogue() {
        return catalog.all().stream()
                .filter(d -> context.isGrantedAll(d.permissions()))
                .toList();
    }

    /** The report, if it exists (404 otherwise) and the caller holds its permissions (403 otherwise). */
    public ReportDefinition authorize(String code) {
        ReportDefinition definition = catalog.find(code).orElseThrow(ApiException::notFound);
        for (String permission : definition.permissions()) {
            if (!context.isGranted(permission)) {
                throw new ApiException(
                        PlatformErrorCode.FORBIDDEN,
                        "The report " + code + " requires the permission " + permission + ".");
            }
        }
        return definition;
    }

    public ReportResult run(String code, MultiValueMap<String, String> query) {
        ReportDefinition definition = authorize(code);
        if (definition.source() != ReportSourceKind.VIEWS) {
            // Accounting serves its statements at the same path; only their exports run here.
            throw ApiException.notFound();
        }
        throttle();
        ReportScope scope = context.scope();
        ReportParameterParser.Request request = parser.parseQuery(
                definition, query, scope.today(), properties.defaultPageSize(), properties.maxPageSize());
        byte[] fingerprint = fingerprint(definition, request.parameters(), request.sort());
        List<@Nullable String> after = request.cursor() == null ? null : position(request.cursor(), fingerprint);
        OffsetDateTime generatedAt = context.now();
        ReportPage page = views.page(
                definition,
                request.parameters(),
                scope,
                request.sort(),
                after,
                request.limit(),
                after == null,
                properties.queryTimeout());
        String next = page.hasMore() ? cursors.encode(fingerprint, page.lastKey()) : null;
        return new ReportResult(
                definition,
                request.parameters().asStrings(),
                page.rows(),
                page.totals(),
                new PageResponse.Page(request.limit(), next, page.hasMore()),
                generatedAt);
    }

    private void throttle() {
        if (!erp.security().rateLimits().enabled()) {
            return;
        }
        FixedWindowRateLimiter.Decision decision = limiter.tryAcquire(
                "report:" + CurrentContext.requireActor().userId(), properties.reportsPerUserPerMinute());
        if (!decision.allowed()) {
            throw new ApiException(
                    PlatformErrorCode.RATE_LIMITED,
                    "Too many report requests. Retry after " + decision.secondsUntilReset() + " seconds.");
        }
    }

    private List<@Nullable String> position(String cursor, byte[] fingerprint) {
        return cursors.decode(cursor)
                .filter(p -> CursorCodec.sameFingerprint(p.fingerprint(), fingerprint))
                .map(CursorCodec.Position::values)
                .orElseThrow(() -> ApiException.badRequest(
                        "The cursor is invalid.",
                        List.of(FieldViolation.atParameter(
                                ReportParameterParser.CURSOR,
                                "INVALID_CURSOR",
                                "is not a cursor of this report with these parameters"))));
    }

    /** A cursor is valid only for the same report, parameters and sort (API.md §8.1). */
    static byte[] fingerprint(ReportDefinition definition, ReportParameters parameters, List<ReportSort> sort) {
        String canonical = "report|" + definition.code() + "|" + parameters.canonical() + "|"
                + sort.stream().map(ReportSort::canonical).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(digest, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
