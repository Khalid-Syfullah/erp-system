package com.erp.accounting.application;

import com.erp.accounting.domain.AccountType;
import com.erp.accounting.domain.MappingKey;
import com.erp.accounting.domain.MappingKey.ScopeType;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.AccountingSettingsRepository;
import com.erp.accounting.persistence.MappingRepository;
import com.erp.inventory.api.InventoryFacade;
import com.erp.org.api.OrgFacade;
import com.erp.partners.api.PartnersFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account mappings (DATABASE.md §5.8, PRODUCT_SPEC.md §8.6): which account each posting rule uses,
 * by default and per scope (category, warehouse, partner group, tax code, department, reason code).
 * The scope entity is validated against its module's facade; the account must suit the key (AR
 * control a RECEIVABLE account, revenue keys a REVENUE account, …). The whole set carries the
 * settings version as its ETag.
 */
@Service
public class AccountMappingService {

    /** A mapping change: {@code accountId == null} removes the scoped mapping. */
    public record Change(
            String mappingKey,
            String scopeType,
            @Nullable UUID scopeId,
            @Nullable UUID accountId) {}

    public record MappingSet(List<AccountingViews.Mapping> mappings, int version) {}

    /** The diagnostic answer of {@code GET …/account-mappings/resolve}. */
    public record Resolution(String mappingKey, UUID accountId, String accountCode) {}

    private final MappingRepository mappings;
    private final AccountRepository accounts;
    private final AccountingSettingsRepository settings;
    private final AccountDetermination determination;
    private final InventoryFacade inventory;
    private final PartnersFacade partners;
    private final OrgFacade org;
    private final AccountingContext context;
    private final AuditPort audit;

    AccountMappingService(
            MappingRepository mappings,
            AccountRepository accounts,
            AccountingSettingsRepository settings,
            AccountDetermination determination,
            InventoryFacade inventory,
            PartnersFacade partners,
            OrgFacade org,
            AccountingContext context,
            AuditPort audit) {
        this.mappings = mappings;
        this.accounts = accounts;
        this.settings = settings;
        this.determination = determination;
        this.inventory = inventory;
        this.partners = partners;
        this.org = org;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public MappingSet get() {
        UUID companyId = context.companyId();
        int version =
                settings.find(companyId).orElseThrow(ApiException::notFound).version();
        return new MappingSet(mappings.all(companyId), version);
    }

    /** Applies the changes (insert, re-point or remove) as one versioned update. */
    @Transactional
    public MappingSet apply(@Nullable String ifMatch, List<Change> changes) {
        UUID companyId = context.companyId();
        mappings.lockMappings(companyId);
        int version =
                settings.find(companyId).orElseThrow(ApiException::notFound).version();
        EntityTags.requireMatch(ifMatch, version);
        List<FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            validate(companyId, "/mappings/" + i, changes.get(i), violations);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The account mappings are invalid.", violations);
        }
        UUID actor = context.actor();
        for (Change change : changes) {
            if (change.accountId() == null) {
                mappings.delete(companyId, change.mappingKey(), change.scopeType(), change.scopeId());
            } else {
                mappings.upsert(
                        companyId,
                        change.mappingKey(),
                        change.scopeType(),
                        change.scopeId(),
                        change.accountId(),
                        actor);
            }
            audit.record(AuditEvent.builder("CONFIG_CHANGE", "accounting")
                    .entity("account_mapping", null, change.mappingKey())
                    .detail("scopeType", change.scopeType())
                    .detail("scopeId", change.scopeId())
                    .detail("accountId", change.accountId())
                    .build());
        }
        if (!settings.touch(companyId, version, actor)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The mappings were modified concurrently.");
        }
        return get();
    }

    /** Which account a key resolves to for a scope (the posting rules' answer). */
    @Transactional(readOnly = true)
    public Resolution resolve(String key, @Nullable String scopeType, @Nullable UUID scopeId) {
        MappingKey mappingKey;
        try {
            mappingKey = MappingKey.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw ApiException.validationFailed(
                    "The mapping key is unknown.",
                    List.of(FieldViolation.atParameter("key", "INVALID_VALUE", "is not a mapping key")));
        }
        List<AccountDetermination.Scope> scopes = new ArrayList<>();
        if (scopeType != null && scopeId != null) {
            ScopeType type = ScopeType.valueOf(scopeType);
            scopes = type == ScopeType.PRODUCT_CATEGORY
                    ? determination.category(scopeId)
                    : List.of(new AccountDetermination.Scope(type, scopeId));
        }
        UUID accountId = determination.resolve(mappingKey, scopes);
        return new Resolution(
                key,
                accountId,
                accounts.find(context.companyId(), accountId).orElseThrow().code());
    }

    private void validate(UUID companyId, String at, Change change, List<FieldViolation> violations) {
        MappingKey key;
        ScopeType scope;
        try {
            key = MappingKey.valueOf(change.mappingKey());
            scope = ScopeType.valueOf(change.scopeType());
        } catch (IllegalArgumentException e) {
            violations.add(FieldViolation.atPointer(at, "INVALID_VALUE", "has an unknown mapping key or scope type"));
            return;
        }
        if (!key.allows(scope)) {
            violations.add(FieldViolation.atPointer(
                    at + "/scopeType", "INVALID_VALUE", key + " cannot be mapped per " + scope));
            return;
        }
        if ((scope == ScopeType.DEFAULT) != (change.scopeId() == null)) {
            violations.add(FieldViolation.atPointer(
                    at + "/scopeId", "INVALID_VALUE", "is required for a scoped mapping and absent for DEFAULT"));
            return;
        }
        if (change.accountId() == null) {
            if (scope == ScopeType.DEFAULT) {
                violations.add(FieldViolation.atPointer(
                        at + "/accountId", "REQUIRED", "a default mapping can be re-pointed but not removed"));
            }
            return;
        }
        if (change.scopeId() != null && !scopeExists(key, scope, change.scopeId())) {
            violations.add(FieldViolation.atPointer(
                    at + "/scopeId", "UNKNOWN_SCOPE", "is not a " + scope + " of the company the key can use"));
        }
        var account = accounts.find(companyId, change.accountId()).orElse(null);
        if (account == null || !account.active() || !account.postable()) {
            violations.add(FieldViolation.atPointer(
                    at + "/accountId", "ACCOUNT_NOT_POSTABLE", "must be an active, postable account"));
        } else if (!key.accepts(AccountType.valueOf(account.accountType()), account.accountSubtype())) {
            violations.add(FieldViolation.atPointer(
                    at + "/accountId",
                    "INVALID_VALUE",
                    "account " + account.code() + " (" + account.accountType() + " / " + account.accountSubtype()
                            + ") cannot serve " + key));
        }
    }

    private boolean scopeExists(MappingKey key, ScopeType scope, UUID id) {
        UUID companyId = context.companyId();
        return switch (scope) {
            case DEFAULT -> true;
            case PRODUCT_CATEGORY -> !inventory.categoryAncestry(id).isEmpty();
            case WAREHOUSE -> inventory.warehouse(id).isPresent();
            case REASON_CODE -> inventory.reasonCode(id).isPresent();
            case PARTNER_GROUP ->
                partners.group(id)
                        .map(g -> g.appliesTo().equals(key.groupKind()))
                        .orElse(false);
            case TAX_CODE -> org.taxCode(companyId, id).isPresent();
            case DEPARTMENT -> org.departmentForUse(companyId, id).isPresent();
            case PAY_COMPONENT -> false;
        };
    }
}
