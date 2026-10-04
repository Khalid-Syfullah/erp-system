package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.application.AccountingCommands;
import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.ChartOfAccountsService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** The chart of accounts (API.md §17.8). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/accounts")
class AccountController {

    private final ChartOfAccountsService accounts;
    private final ListQueryParser parser;

    AccountController(ChartOfAccountsService accounts, ListQueryParser parser) {
        this.accounts = accounts;
        this.parser = parser;
    }

    record AccountRequest(
            @NotNull @Pattern(regexp = "^[0-9A-Z.\\-]{1,20}$") String code,

            @NotBlank @Size(max = 150) String name,

            @NotNull @Pattern(regexp = "^(ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE)$")
            String accountType,

            @NotBlank @Size(max = 40) String accountSubtype,
            @Nullable UUID parentId,
            @Nullable Boolean isPostable,
            @Pattern(regexp = "^[A-Z]{3}$") @Nullable String currencyCode,
            @Size(max = 500) @Nullable String description) {}

    @RequiresPermission(AccountingPermissions.ACCOUNT_READ)
    @GetMapping
    PageResponse<AccountingResponses.Account> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        MultiValueMap<String, String> query = new org.springframework.util.LinkedMultiValueMap<>(parameters);
        query.remove("view");
        return accounts.list(parser.parse(query, AccountingListings.ACCOUNTS)).map(AccountingResponses.Account::from);
    }

    /** The whole chart as a tree ({@code ?view=tree} in API.md §17.8). */
    @RequiresPermission(AccountingPermissions.ACCOUNT_READ)
    @GetMapping("/tree")
    List<AccountingResponses.AccountNode> tree(@PathVariable UUID companyId) {
        return accounts.tree().stream()
                .map(AccountingResponses.AccountNode::from)
                .toList();
    }

    @RequiresPermission(AccountingPermissions.ACCOUNT_READ)
    @GetMapping("/{accountId}")
    ResponseEntity<AccountingResponses.Account> get(@PathVariable UUID companyId, @PathVariable UUID accountId) {
        return AccountingResponses.Account.entity(accounts.get(accountId));
    }

    @RequiresPermission(AccountingPermissions.ACCOUNT_MANAGE)
    @PostMapping
    ResponseEntity<AccountingResponses.Account> create(
            @PathVariable UUID companyId, @Valid @RequestBody AccountRequest request) {
        var created = accounts.create(new AccountingCommands.Account(
                request.code(),
                request.name().strip(),
                request.accountType(),
                request.accountSubtype(),
                request.parentId(),
                !Boolean.FALSE.equals(request.isPostable()),
                request.currencyCode(),
                request.description()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/accounts/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(AccountingResponses.Account.from(created));
    }

    @RequiresPermission(AccountingPermissions.ACCOUNT_MANAGE)
    @PatchMapping(path = "/{accountId}", consumes = AccountingResponses.MERGE_PATCH)
    ResponseEntity<AccountingResponses.Account> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return AccountingResponses.Account.entity(accounts.patch(accountId, ifMatch, patch));
    }

    @RequiresPermission(AccountingPermissions.ACCOUNT_MANAGE)
    @PostMapping("/{accountId}/deactivate")
    ResponseEntity<AccountingResponses.Account> deactivate(
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return AccountingResponses.Account.entity(accounts.setActive(accountId, ifMatch, false));
    }

    @RequiresPermission(AccountingPermissions.ACCOUNT_MANAGE)
    @PostMapping("/{accountId}/activate")
    ResponseEntity<AccountingResponses.Account> activate(
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return AccountingResponses.Account.entity(accounts.setActive(accountId, ifMatch, true));
    }
}
