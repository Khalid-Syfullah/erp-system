package com.erp.partners.web;

import com.erp.partners.PartnersPermissions;
import com.erp.partners.application.BankAccountService;
import com.erp.partners.application.PartnerCommands;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner bank accounts (API.md §17.4, SECURITY.md §7): masked lists, add and remove with
 * {@code manage_bank}, reveal with {@code read_bank} plus step-up re-authentication.
 */
@RestController
class BankAccountController {

    private static final String P = ApiPaths.V1 + "/companies/{companyId}/partners/{partnerId}/bank-accounts";

    private final BankAccountService accounts;

    BankAccountController(BankAccountService accounts) {
        this.accounts = accounts;
    }

    record BankAccountRequest(
            @NotBlank @Size(max = 100) String bankName,

            @NotBlank @Size(max = 200) String accountHolder,

            @NotBlank @Size(max = 50) String accountNumber,
            @Size(max = 50) @Nullable String iban,

            @Pattern(regexp = "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$") @Nullable String swiftBic,

            @Pattern(regexp = "^[A-Z]{3}$") @Nullable String currencyCode,
            @Nullable Boolean isDefault) {}

    @RequiresPermission(PartnersPermissions.BANK_READ)
    @GetMapping(P)
    PartnersResponses.ListResponse<PartnersResponses.BankAccount> list(
            @PathVariable UUID companyId, @PathVariable UUID partnerId) {
        return new PartnersResponses.ListResponse<>(accounts.list(partnerId).stream()
                .map(PartnersResponses.BankAccount::from)
                .toList());
    }

    @RequiresPermission(PartnersPermissions.BANK_MANAGE)
    @PostMapping(P)
    ResponseEntity<PartnersResponses.BankAccount> add(
            @PathVariable UUID companyId,
            @PathVariable UUID partnerId,
            @Valid @RequestBody BankAccountRequest request) {
        var created = accounts.add(
                partnerId,
                new PartnerCommands.BankAccount(
                        request.bankName().strip(),
                        request.accountHolder().strip(),
                        request.accountNumber(),
                        PartnerController.blankToNull(request.iban()),
                        request.swiftBic(),
                        request.currencyCode(),
                        Boolean.TRUE.equals(request.isDefault())));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/partners/" + partnerId
                        + "/bank-accounts/" + created.id()))
                .body(PartnersResponses.BankAccount.from(created));
    }

    @RequiresPermission(PartnersPermissions.BANK_MANAGE)
    @DeleteMapping(P + "/{accountId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId, @PathVariable UUID partnerId, @PathVariable UUID accountId) {
        accounts.delete(partnerId, accountId);
        return ResponseEntity.noContent().build();
    }

    /** Audit-logged reveal of the full numbers; requires a password confirmation in the last 5 minutes. */
    @RequiresPermission(PartnersPermissions.BANK_READ)
    @PostMapping(P + "/{accountId}/reveal")
    PartnersResponses.RevealedBankAccount reveal(
            @PathVariable UUID companyId, @PathVariable UUID partnerId, @PathVariable UUID accountId) {
        var revealed = accounts.reveal(partnerId, accountId);
        return new PartnersResponses.RevealedBankAccount(revealed.id(), revealed.accountNumber(), revealed.iban());
    }
}
