package com.erp.accounting.application;

import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.AccountingSettingsRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounting settings (DATABASE.md §5.8): the retained earnings account the year-end close books
 * into, whether manual entries may go into soft-closed periods, the rounding tolerance of system
 * entries, and the amount from which a manual entry is posted by someone other than its creator.
 */
@Service
public class AccountingSettingsService {

    private final AccountingSettingsRepository settings;
    private final AccountRepository accounts;
    private final AccountingContext context;
    private final AuditPort audit;

    AccountingSettingsService(
            AccountingSettingsRepository settings,
            AccountRepository accounts,
            AccountingContext context,
            AuditPort audit) {
        this.settings = settings;
        this.accounts = accounts;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public AccountingViews.Settings get() {
        return current(context.companyId());
    }

    AccountingViews.Settings current(UUID companyId) {
        return settings.find(companyId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public AccountingViews.Settings replace(@Nullable String ifMatch, AccountingCommands.Settings command) {
        UUID companyId = context.companyId();
        AccountingViews.Settings current = current(companyId);
        EntityTags.requireMatch(ifMatch, current.version());
        List<FieldViolation> violations = new ArrayList<>();
        var account =
                accounts.find(companyId, command.retainedEarningsAccountId()).orElse(null);
        if (account == null
                || !account.active()
                || !account.postable()
                || !"RETAINED_EARNINGS".equals(account.accountSubtype())) {
            violations.add(FieldViolation.atPointer(
                    "/retainedEarningsAccountId",
                    "INVALID_VALUE",
                    "must be an active, postable account of subtype RETAINED_EARNINGS"));
        }
        if (command.maxRoundingDifferenceMinorUnits() < 0 || command.maxRoundingDifferenceMinorUnits() > 100) {
            violations.add(FieldViolation.atPointer(
                    "/maxRoundingDifferenceMinorUnits", "INVALID_VALUE", "must be between 0 and 100"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The settings are invalid.", violations);
        }
        if (!settings.update(
                companyId,
                current.version(),
                command.retainedEarningsAccountId(),
                command.allowManualEntriesInSoftClosed(),
                command.maxRoundingDifferenceMinorUnits(),
                command.manualEntryApprovalThresholdBase(),
                context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The settings were modified concurrently.");
        }
        AccountingViews.Settings after = current(companyId);
        audit.record(AuditEvent.builder("CONFIG_CHANGE", "accounting")
                .entity("accounting_settings", companyId, null)
                .change(
                        "retainedEarningsAccountId",
                        current.retainedEarningsAccountId(),
                        after.retainedEarningsAccountId())
                .change(
                        "allowManualEntriesInSoftClosed",
                        current.allowManualEntriesInSoftClosed(),
                        after.allowManualEntriesInSoftClosed())
                .change(
                        "maxRoundingDifferenceMinorUnits",
                        current.maxRoundingDifferenceMinorUnits(),
                        after.maxRoundingDifferenceMinorUnits())
                .change(
                        "manualEntryApprovalThresholdBase",
                        Objects.toString(current.manualEntryApprovalThresholdBase(), null),
                        Objects.toString(after.manualEntryApprovalThresholdBase(), null))
                .build());
        return after;
    }
}
