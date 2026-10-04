package com.erp.accounting.application;

import com.erp.accounting.domain.AccountType;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.CompanyBankAccountRepository;
import com.erp.accounting.persistence.MappingRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The chart of accounts (PRODUCT_SPEC.md §8.1): a per-company tree whose leaves are postable and
 * whose group accounts aggregate. The subtype fixes whether an account is a control account (system
 * postings only). An account with postings keeps its type and subtype; system accounts the posting
 * rules rely on are never deactivated, nor is an account a mapping or bank account uses.
 */
@Service
public class ChartOfAccountsService {

    static final Set<String> PATCHABLE =
            Set.of("name", "accountSubtype", "parentId", "isPostable", "currencyCode", "description");

    /** A node of the account tree ({@code ?view=tree}). */
    public record Node(AccountingViews.Account account, List<Node> children) {}

    private final AccountRepository accounts;
    private final MappingRepository mappings;
    private final CompanyBankAccountRepository bankAccounts;
    private final AccountingContext context;
    private final AuditPort audit;

    ChartOfAccountsService(
            AccountRepository accounts,
            MappingRepository mappings,
            CompanyBankAccountRepository bankAccounts,
            AccountingContext context,
            AuditPort audit) {
        this.accounts = accounts;
        this.mappings = mappings;
        this.bankAccounts = bankAccounts;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingViews.Account> list(ListQuery query) {
        return accounts.list(context.companyId(), query);
    }

    /** All accounts as a tree, ordered by code. */
    @Transactional(readOnly = true)
    public List<Node> tree() {
        List<AccountingViews.Account> all = accounts.all(context.companyId());
        Map<UUID, List<AccountingViews.Account>> children = new LinkedHashMap<>();
        all.forEach(a -> {
            if (a.parentId() != null) {
                children.computeIfAbsent(a.parentId(), k -> new ArrayList<>()).add(a);
            }
        });
        return all.stream()
                .filter(a -> a.parentId() == null)
                .map(a -> node(a, children, 0))
                .toList();
    }

    private static Node node(
            AccountingViews.Account account, Map<UUID, List<AccountingViews.Account>> children, int depth) {
        if (depth > 50) {
            return new Node(account, List.of());
        }
        return new Node(
                account,
                children.getOrDefault(account.id(), List.of()).stream()
                        .map(c -> node(c, children, depth + 1))
                        .toList());
    }

    @Transactional(readOnly = true)
    public AccountingViews.Account get(UUID id) {
        return accounts.find(context.companyId(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public AccountingViews.Account create(AccountingCommands.Account command) {
        UUID companyId = context.companyId();
        List<FieldViolation> violations = new ArrayList<>();
        AccountType type = type(command.accountType(), violations);
        check(companyId, null, type, command.accountSubtype(), command.parentId(), command.currencyCode(), violations);
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The account is invalid.", violations);
        }
        UUID id = accounts.insert(
                companyId,
                new AccountRepository.Values(
                        command.code(),
                        command.name(),
                        command.accountType(),
                        command.accountSubtype(),
                        command.parentId(),
                        command.postable(),
                        AccountType.CONTROL_SUBTYPES.contains(command.accountSubtype()),
                        false,
                        command.currencyCode(),
                        command.description()),
                context.actor());
        audit.record(AuditEvent.builder("CREATE", "accounting")
                .entity("account", id, command.code())
                .detail("accountType", command.accountType())
                .detail("accountSubtype", command.accountSubtype())
                .detail("parentId", command.parentId())
                .detail("postable", command.postable())
                .build());
        return get(id);
    }

    @Transactional
    public AccountingViews.Account patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        AccountingViews.Account current = accounts.lock(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 150);
        var subtype = patch.text("accountSubtype", true, 40);
        var parent = patch.uuid("parentId", false);
        var postable = patch.bool("isPostable");
        var currency = patch.text("currencyCode", false, 3, v -> v.matches("^[A-Z]{3}$") ? null : "must be ISO 4217");
        var description = patch.text("description", false, 500);
        patch.throwIfInvalid();
        String nextSubtype = Objects.requireNonNull(subtype.orElse(current.accountSubtype()));
        boolean nextPostable = Boolean.TRUE.equals(postable.orElse(current.postable()));
        UUID nextParent = parent.orElse(current.parentId());
        String nextCurrency = currency.orElse(current.currencyCode());
        List<FieldViolation> violations = new ArrayList<>();
        AccountType type = AccountType.valueOf(current.accountType());
        check(companyId, id, type, nextSubtype, nextParent, nextCurrency, violations);
        boolean used = accounts.hasPostings(companyId, id);
        if (!nextSubtype.equals(current.accountSubtype()) && (used || current.system())) {
            violations.add(FieldViolation.atPointer(
                    "/accountSubtype", "ACCOUNT_IN_USE", "cannot change once the account has postings"));
        }
        if (!Objects.equals(nextCurrency, current.currencyCode()) && used) {
            violations.add(FieldViolation.atPointer(
                    "/currencyCode", "ACCOUNT_IN_USE", "cannot change once the account has postings"));
        }
        if (nextPostable != current.postable()) {
            if (!nextPostable && (used || current.system())) {
                violations.add(FieldViolation.atPointer(
                        "/isPostable", "ACCOUNT_IN_USE", "an account with postings stays postable"));
            }
            if (nextPostable && accounts.hasChildren(companyId, id)) {
                violations.add(FieldViolation.atPointer(
                        "/isPostable", "HAS_CHILDREN", "a group account with children cannot become postable"));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The account is invalid.", violations);
        }
        AccountRepository.Values next = new AccountRepository.Values(
                current.code(),
                Objects.requireNonNull(name.orElse(current.name())),
                current.accountType(),
                nextSubtype,
                nextParent,
                nextPostable,
                AccountType.CONTROL_SUBTYPES.contains(nextSubtype),
                current.system(),
                nextCurrency,
                description.orElse(current.description()));
        if (!accounts.update(companyId, id, current.version(), next, context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The account was modified concurrently.");
        }
        AccountingViews.Account after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "accounting")
                .entity("account", id, current.code())
                .change("name", current.name(), after.name())
                .change("accountSubtype", current.accountSubtype(), after.accountSubtype())
                .change("parentId", current.parentId(), after.parentId())
                .change("postable", current.postable(), after.postable())
                .change("currencyCode", current.currencyCode(), after.currencyCode())
                .change("description", current.description(), after.description())
                .build());
        return after;
    }

    @Transactional
    public AccountingViews.Account setActive(UUID id, @Nullable String ifMatch, boolean active) {
        UUID companyId = context.companyId();
        AccountingViews.Account current = accounts.lock(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (current.active() == active) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "The account is " + current.status().toLowerCase() + " already.");
        }
        if (!active) {
            List<String> uses = new ArrayList<>();
            if (current.system()) {
                uses.add("the posting rules (system account)");
            }
            if (mappings.usesAccount(companyId, id)) {
                uses.add("account mappings");
            }
            if (bankAccounts.byGlAccount(companyId, id).isPresent()) {
                uses.add("a bank account");
            }
            if (!uses.isEmpty()) {
                throw new ApiException(
                        PlatformErrorCode.RESOURCE_IN_USE, "The account is used by " + String.join(", ", uses) + ".");
            }
        }
        String status = active ? "ACTIVE" : "INACTIVE";
        if (!accounts.setStatus(companyId, id, current.version(), status, context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The account was modified concurrently.");
        }
        audit.record(AuditEvent.builder("STATE_CHANGE", "accounting")
                .entity("account", id, current.code())
                .transition(current.status(), status)
                .build());
        return get(id);
    }

    private static @Nullable AccountType type(String value, List<FieldViolation> violations) {
        try {
            return AccountType.valueOf(value);
        } catch (IllegalArgumentException e) {
            violations.add(FieldViolation.atPointer(
                    "/accountType", "INVALID_VALUE", "must be ASSET, LIABILITY, EQUITY, REVENUE or EXPENSE"));
            return null;
        }
    }

    private void check(
            UUID companyId,
            @Nullable UUID self,
            @Nullable AccountType type,
            String subtype,
            @Nullable UUID parentId,
            @Nullable String currencyCode,
            List<FieldViolation> violations) {
        if (type != null && !type.allows(subtype)) {
            violations.add(FieldViolation.atPointer(
                    "/accountSubtype",
                    "INVALID_VALUE",
                    "must be one of " + type.subtypes().stream().sorted().toList() + " for " + type));
        }
        if (parentId != null) {
            var parent = accounts.find(companyId, parentId).orElse(null);
            if (parent == null || parent.id().equals(self)) {
                violations.add(
                        FieldViolation.atPointer("/parentId", "UNKNOWN_ACCOUNT", "is not an account of the company"));
            } else if (parent.postable()) {
                violations.add(FieldViolation.atPointer(
                        "/parentId", "PARENT_POSTABLE", "must be a group (non-postable) account"));
            } else if (type != null && !parent.accountType().equals(type.name())) {
                violations.add(
                        FieldViolation.atPointer("/parentId", "TYPE_MISMATCH", "must be an account of the same type"));
            }
        }
        if (currencyCode != null && !context.currencyUsable(currencyCode)) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }
    }
}
