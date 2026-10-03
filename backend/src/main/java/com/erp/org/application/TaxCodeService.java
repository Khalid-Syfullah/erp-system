package com.erp.org.application;

import com.erp.org.persistence.TaxCodeRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Tax codes of the active company (PRODUCT_SPEC.md §4.2): never deleted; once used by a document
 * (reported through the {@code TaxCodeUsage} port), the rate, scope and exemption are frozen and a
 * change needs a new code with validity dates.
 */
@Service
public class TaxCodeService {

    public static final BigDecimal MAX_RATE_PERCENT = new BigDecimal("100");
    public static final int RATE_SCALE = 4;
    static final Set<String> SCOPES = Set.of("SALES", "PURCHASE", "BOTH");
    static final Set<String> PATCHABLE = Set.of("name", "scope", "ratePercent", "isExempt", "validFrom", "validTo");

    private final TaxCodeRepository taxCodes;
    private final OrgUsageChecks usage;
    private final AuditPort audit;

    public TaxCodeService(TaxCodeRepository taxCodes, OrgUsageChecks usage, AuditPort audit) {
        this.taxCodes = taxCodes;
        this.usage = usage;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<TaxCodeView> list(ListQuery query) {
        return taxCodes.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public TaxCodeView get(UUID id) {
        return taxCodes.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public TaxCodeView create(ReferenceCommands.TaxCode command) {
        List<FieldViolation> violations = new ArrayList<>();
        validate(command, violations);
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The tax code is invalid.", violations);
        }
        UUID id = taxCodes.insert(
                CurrentContext.requireCompany(),
                command,
                CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "org")
                .entity("tax_code", id, command.code())
                .detail("scope", command.scope())
                .detail("ratePercent", command.ratePercent().toPlainString())
                .detail("isExempt", command.exempt())
                .detail("validFrom", Objects.toString(command.validFrom(), null))
                .detail("validTo", Objects.toString(command.validTo(), null))
                .build());
        return get(id);
    }

    @Transactional
    public TaxCodeView patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        TaxCodeView current = taxCodes.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 100);
        var scope = patch.text("scope", true, 10, s -> SCOPES.contains(s) ? null : "must be SALES, PURCHASE or BOTH");
        var rate = patch.decimal("ratePercent", BigDecimal.ZERO, MAX_RATE_PERCENT, RATE_SCALE);
        var exempt = patch.bool("isExempt");
        var validFrom = patch.date("validFrom", false);
        var validTo = patch.date("validTo", false);
        patch.throwIfInvalid();

        ReferenceCommands.TaxCode next = new ReferenceCommands.TaxCode(
                current.code(),
                name.orElse(current.name()),
                scope.orElse(current.scope()),
                rate.orElse(current.ratePercent()),
                exempt.orElse(current.exempt()),
                validFrom.orElse(current.validFrom()),
                validTo.orElse(current.validTo()),
                current.active());
        List<FieldViolation> violations = new ArrayList<>();
        validate(next, violations);
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The tax code is invalid.", violations);
        }
        boolean taxChanged = next.ratePercent().compareTo(current.ratePercent()) != 0
                || !next.scope().equals(current.scope())
                || next.exempt() != current.exempt();
        if (taxChanged && usage.isTaxCodeUsed(companyId, id)) {
            throw new ApiException(
                    PlatformErrorCode.RESOURCE_IN_USE,
                    "The tax code is used by documents; its rate, scope and exemption cannot change. End its"
                            + " validity and create a new code instead.");
        }
        update(current, next);
        TaxCodeView after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "org")
                .entity("tax_code", id, after.code())
                .change("name", current.name(), after.name())
                .change("scope", current.scope(), after.scope())
                .change(
                        "ratePercent",
                        current.ratePercent().toPlainString(),
                        after.ratePercent().toPlainString())
                .change("isExempt", current.exempt(), after.exempt())
                .change(
                        "validFrom",
                        Objects.toString(current.validFrom(), null),
                        Objects.toString(after.validFrom(), null))
                .change("validTo", Objects.toString(current.validTo(), null), Objects.toString(after.validTo(), null))
                .build());
        return after;
    }

    @Transactional
    public TaxCodeView setActive(UUID id, @Nullable String ifMatch, boolean active) {
        UUID companyId = CurrentContext.requireCompany();
        TaxCodeView current = taxCodes.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (current.active() == active) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "The tax code is already " + (active ? "active." : "inactive."));
        }
        update(
                current,
                new ReferenceCommands.TaxCode(
                        current.code(),
                        current.name(),
                        current.scope(),
                        current.ratePercent(),
                        current.exempt(),
                        current.validFrom(),
                        current.validTo(),
                        active));
        audit.record(AuditEvent.builder("STATE_CHANGE", "org")
                .entity("tax_code", id, current.code())
                .transition(current.active() ? "ACTIVE" : "INACTIVE", active ? "ACTIVE" : "INACTIVE")
                .build());
        return get(id);
    }

    private static void validate(ReferenceCommands.TaxCode c, List<FieldViolation> violations) {
        if (c.exempt() && c.ratePercent().signum() != 0) {
            violations.add(FieldViolation.atPointer("/ratePercent", "INVALID_VALUE", "must be 0 for an exempt code"));
        }
        if (c.ratePercent().stripTrailingZeros().scale() > RATE_SCALE) {
            violations.add(FieldViolation.atPointer(
                    "/ratePercent", "INVALID_VALUE", "must have at most " + RATE_SCALE + " decimal places"));
        }
        if (c.validFrom() != null && c.validTo() != null && c.validTo().isBefore(c.validFrom())) {
            violations.add(FieldViolation.atPointer("/validTo", "INVALID_VALUE", "must not be before validFrom"));
        }
    }

    private void update(TaxCodeView current, ReferenceCommands.TaxCode next) {
        if (!taxCodes.update(
                current.companyId(),
                current.id(),
                current.version(),
                CurrentContext.requireActor().userId(),
                next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The tax code was modified concurrently.");
        }
    }
}
