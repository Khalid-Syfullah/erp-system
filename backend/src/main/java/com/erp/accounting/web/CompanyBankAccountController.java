package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.application.AccountingCommands;
import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.AccountingReports;
import com.erp.accounting.application.CompanyBankAccountService;
import com.erp.accounting.application.ReportService;
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
import java.time.LocalDate;
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

/** The company's bank and cash accounts and their transactions (API.md §17.8). */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/bank-accounts")
class CompanyBankAccountController {

    private final CompanyBankAccountService bankAccounts;
    private final ReportService reports;
    private final ListQueryParser parser;

    CompanyBankAccountController(
            CompanyBankAccountService bankAccounts, ReportService reports, ListQueryParser parser) {
        this.bankAccounts = bankAccounts;
        this.reports = reports;
        this.parser = parser;
    }

    record BankAccountRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull UUID accountId,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
            @Size(max = 100) @Nullable String bankName,
            @Size(max = 40) @Nullable String accountNumber,
            @Size(max = 40) @Nullable String iban) {}

    @RequiresPermission(AccountingPermissions.BANK_ACCOUNT_READ)
    @GetMapping
    PageResponse<AccountingResponses.BankAccount> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return bankAccounts
                .list(parser.parse(parameters, AccountingListings.BANK_ACCOUNTS))
                .map(AccountingResponses.BankAccount::from);
    }

    @RequiresPermission(AccountingPermissions.BANK_ACCOUNT_READ)
    @GetMapping("/{bankAccountId}")
    ResponseEntity<AccountingResponses.BankAccount> get(
            @PathVariable UUID companyId, @PathVariable UUID bankAccountId) {
        return AccountingResponses.BankAccount.entity(bankAccounts.get(bankAccountId));
    }

    /** The account's posted transactions with a running balance and reconciliation marks. */
    @RequiresPermission(AccountingPermissions.BANK_ACCOUNT_READ)
    @GetMapping("/{bankAccountId}/transactions")
    AccountingReports.CashBook transactions(
            @PathVariable UUID companyId,
            @PathVariable UUID bankAccountId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return reports.cashBook(bankAccountId, from, to);
    }

    @RequiresPermission(AccountingPermissions.BANK_ACCOUNT_MANAGE)
    @PostMapping
    ResponseEntity<AccountingResponses.BankAccount> create(
            @PathVariable UUID companyId, @Valid @RequestBody BankAccountRequest request) {
        var created = bankAccounts.create(new AccountingCommands.BankAccount(
                request.name().strip(),
                request.accountId(),
                request.currencyCode(),
                request.bankName(),
                request.accountNumber(),
                request.iban()));
        return ResponseEntity.created(
                        URI.create(ApiPaths.V1 + "/companies/" + companyId + "/bank-accounts/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(AccountingResponses.BankAccount.from(created));
    }

    @RequiresPermission(AccountingPermissions.BANK_ACCOUNT_MANAGE)
    @PatchMapping(path = "/{bankAccountId}", consumes = AccountingResponses.MERGE_PATCH)
    ResponseEntity<AccountingResponses.BankAccount> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID bankAccountId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return AccountingResponses.BankAccount.entity(bankAccounts.patch(bankAccountId, ifMatch, patch));
    }
}
