package com.erp.accounting.application;

import com.erp.accounting.api.AccountingReports;
import com.erp.accounting.domain.AccountType;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.CompanyBankAccountRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.banking.BankAccountNumbers;
import com.erp.platform.crypto.FieldEncryptor;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.nio.charset.StandardCharsets;
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
 * The company's bank and cash accounts (PRODUCT_SPEC.md §8.8): each is booked on its own BANK or CASH
 * GL account in the account's currency, which payments and expenses through it must use. Account
 * numbers and IBANs are field-encrypted (SECURITY.md §7); only the last four characters are shown.
 */
@Service
public class CompanyBankAccountService {

    static final Set<String> PATCHABLE = Set.of("name", "bankName", "isActive");

    private final CompanyBankAccountRepository bankAccounts;
    private final AccountRepository accounts;
    private final FieldEncryptor encryptor;
    private final AccountingContext context;
    private final AuditPort audit;

    CompanyBankAccountService(
            CompanyBankAccountRepository bankAccounts,
            AccountRepository accounts,
            FieldEncryptor encryptor,
            AccountingContext context,
            AuditPort audit) {
        this.bankAccounts = bankAccounts;
        this.accounts = accounts;
        this.encryptor = encryptor;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingReports.BankAccount> list(ListQuery query) {
        return bankAccounts.list(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public AccountingReports.BankAccount get(UUID id) {
        return bankAccounts.find(context.companyId(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public AccountingReports.BankAccount create(AccountingCommands.BankAccount command) {
        UUID companyId = context.companyId();
        List<FieldViolation> violations = new ArrayList<>();
        var account = accounts.lock(companyId, command.accountId()).orElse(null);
        String base = context.profile().baseCurrency();
        if (account == null
                || !account.active()
                || !account.postable()
                || !AccountType.MONEY_SUBTYPES.contains(account.accountSubtype())) {
            violations.add(FieldViolation.atPointer(
                    "/accountId", "INVALID_VALUE", "must be an active, postable BANK or CASH account"));
        } else if (account.currencyCode() == null
                ? !command.currencyCode().equals(base)
                : !account.currencyCode().equals(command.currencyCode())) {
            violations.add(FieldViolation.atPointer(
                    "/currencyCode",
                    "CURRENCY_MISMATCH",
                    "must be the GL account's currency ("
                            + (account.currencyCode() == null ? base : account.currencyCode()) + ")"));
        }
        if (!context.currencyUsable(command.currencyCode())) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }
        String number = BankAccountNumbers.normalize(command.accountNumber());
        String iban = BankAccountNumbers.normalize(command.iban());
        if (number != null && !BankAccountNumbers.isValidAccountNumber(number)) {
            violations.add(
                    FieldViolation.atPointer("/accountNumber", "INVALID_VALUE", "must be 4 to 34 letters or digits"));
        }
        if (iban != null && !BankAccountNumbers.isValidIban(iban)) {
            violations.add(FieldViolation.atPointer("/iban", "INVALID_VALUE", "is not a valid IBAN"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The bank account is invalid.", violations);
        }
        UUID id = bankAccounts.newId();
        byte[] numberEncrypted = number == null
                ? null
                : encryptor.encrypt(number.getBytes(StandardCharsets.UTF_8), associatedData("account_number", id));
        byte[] ibanEncrypted = iban == null
                ? null
                : encryptor.encrypt(iban.getBytes(StandardCharsets.UTF_8), associatedData("iban", id));
        bankAccounts.insert(
                companyId,
                id,
                command.name(),
                command.accountId(),
                command.currencyCode(),
                command.bankName(),
                numberEncrypted,
                ibanEncrypted,
                number == null && iban == null ? null : (short) encryptor.activeKeyVersion(),
                number == null ? null : BankAccountNumbers.last4(number),
                context.actor());
        audit.record(AuditEvent.builder("CREATE", "accounting")
                .entity("bank_account", id, command.name())
                .detail("accountId", command.accountId())
                .detail("currencyCode", command.currencyCode())
                .detail("last4", number == null ? null : BankAccountNumbers.last4(number))
                .redactedChange("accountNumber")
                .redactedChange("iban")
                .build());
        return get(id);
    }

    @Transactional
    public AccountingReports.BankAccount patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        AccountingReports.BankAccount current = bankAccounts.lock(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 100);
        var bankName = patch.text("bankName", false, 100);
        var active = patch.bool("isActive");
        patch.throwIfInvalid();
        String nextName = Objects.requireNonNull(name.orElse(current.name()));
        String nextBank = bankName.orElse(current.bankName());
        boolean nextActive = Boolean.TRUE.equals(active.orElse(current.active()));
        if (!bankAccounts.update(companyId, id, current.version(), nextName, nextBank, nextActive, context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The bank account was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "accounting")
                .entity("bank_account", id, current.name())
                .change("name", current.name(), nextName)
                .change("bankName", current.bankName(), nextBank)
                .change("isActive", current.active(), nextActive)
                .build());
        return get(id);
    }

    /** The account locked for use by a payment or expense (active, in the given currency if any). */
    AccountingReports.BankAccount forUse(UUID id, String pointer) {
        AccountingReports.BankAccount account =
                bankAccounts.forUse(context.companyId(), id).orElse(null);
        if (account == null || !account.active()) {
            throw ApiException.validationFailed(
                    "The bank account is unknown or inactive.",
                    List.of(FieldViolation.atPointer(pointer, "INVALID_VALUE", "must be an active bank account")));
        }
        return account;
    }

    private static String associatedData(String column, UUID id) {
        return "accounting.bank_accounts." + column + ":" + id;
    }
}
