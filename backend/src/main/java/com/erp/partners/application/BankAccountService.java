package com.erp.partners.application;

import com.erp.org.api.OrgFacade;
import com.erp.partners.persistence.BankAccountRepository;
import com.erp.partners.persistence.PartnerRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.banking.BankAccountNumbers;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.crypto.FieldEncryptor;
import com.erp.platform.security.ReauthenticationGuard;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partner bank accounts (SECURITY.md §7): the account number and IBAN are field-encrypted with the
 * row ID in the associated data; lists show only the last four characters. Revealing needs step-up
 * re-authentication and writes a {@code VIEW_SENSITIVE} audit record. Changes are audited with the
 * values redacted. Accounts are added or removed, never edited, so every change is visible.
 */
@Service
public class BankAccountService {

    /** The revealed numbers. */
    public record Revealed(
            UUID id, String accountNumber, @Nullable String iban) {}

    private final BankAccountRepository accounts;
    private final PartnerRepository partners;
    private final FieldEncryptor encryptor;
    private final OrgFacade org;
    private final AuditPort audit;
    private final Clock clock;

    BankAccountService(
            BankAccountRepository accounts,
            PartnerRepository partners,
            FieldEncryptor encryptor,
            OrgFacade org,
            AuditPort audit,
            Clock clock) {
        this.accounts = accounts;
        this.partners = partners;
        this.encryptor = encryptor;
        this.org = org;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PartnerViews.BankAccount> list(UUID partnerId) {
        UUID companyId = CurrentContext.requireCompany();
        partners.find(companyId, partnerId).orElseThrow(ApiException::notFound);
        return accounts.list(companyId, partnerId);
    }

    @Transactional
    public PartnerViews.BankAccount add(UUID partnerId, PartnerCommands.BankAccount command) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner =
                partners.lockForChange(companyId, partnerId).orElseThrow(ApiException::notFound);
        String number = BankAccountNumbers.normalize(command.accountNumber());
        String iban = BankAccountNumbers.normalize(command.iban());
        List<FieldViolation> violations = new ArrayList<>();
        if (number == null || !BankAccountNumbers.isValidAccountNumber(number)) {
            violations.add(
                    FieldViolation.atPointer("/accountNumber", "INVALID_VALUE", "must be 4 to 34 letters or digits"));
        }
        if (iban != null && !BankAccountNumbers.isValidIban(iban)) {
            violations.add(FieldViolation.atPointer("/iban", "INVALID_IBAN", "is not a valid IBAN"));
        }
        if (command.currencyCode() != null
                && !org.currency(command.currencyCode()).map(c -> c.active()).orElse(false)) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The bank account is invalid.", violations);
        }
        if (command.isDefault()) {
            accounts.clearDefault(companyId, partnerId, null);
        }
        UUID id = accounts.newId();
        byte[] numberEncrypted =
                encryptor.encrypt(number.getBytes(StandardCharsets.UTF_8), associatedData("account_number", id));
        byte[] ibanEncrypted = iban == null
                ? null
                : encryptor.encrypt(iban.getBytes(StandardCharsets.UTF_8), associatedData("iban", id));
        accounts.insert(
                companyId,
                partnerId,
                id,
                command.bankName(),
                command.accountHolder(),
                numberEncrypted,
                ibanEncrypted,
                command.swiftBic(),
                BankAccountNumbers.last4(number),
                encryptor.activeKeyVersion(),
                command.currencyCode(),
                command.isDefault(),
                actor());
        audit.record(AuditEvent.builder("CREATE", "partners")
                .entity("partner_bank_account", id, partner.code())
                .detail("bankName", command.bankName())
                .detail("last4", BankAccountNumbers.last4(number))
                .redactedChange("accountNumber")
                .redactedChange("iban")
                .build());
        return accounts.find(companyId, partnerId, id).orElseThrow();
    }

    @Transactional
    public void delete(UUID partnerId, UUID accountId) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner =
                partners.lockForChange(companyId, partnerId).orElseThrow(ApiException::notFound);
        PartnerViews.BankAccount account =
                accounts.find(companyId, partnerId, accountId).orElseThrow(ApiException::notFound);
        accounts.delete(companyId, partnerId, accountId);
        audit.record(AuditEvent.builder("DELETE", "partners")
                .entity("partner_bank_account", accountId, partner.code())
                .detail("last4", account.last4())
                .redactedChange("accountNumber")
                .build());
    }

    /** Decrypts the numbers for the caller after a recent password confirmation. */
    @Transactional
    public Revealed reveal(UUID partnerId, UUID accountId) {
        ReauthenticationGuard.require(CurrentContext.requireActor(), clock.instant());
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner = partners.find(companyId, partnerId).orElseThrow(ApiException::notFound);
        PartnerViews.EncryptedBankAccount stored =
                accounts.findEncrypted(companyId, partnerId, accountId).orElseThrow(ApiException::notFound);
        String number = new String(
                encryptor.decrypt(stored.accountNumberEncrypted(), associatedData("account_number", accountId)),
                StandardCharsets.UTF_8);
        String iban = stored.ibanEncrypted() == null
                ? null
                : new String(
                        encryptor.decrypt(stored.ibanEncrypted(), associatedData("iban", accountId)),
                        StandardCharsets.UTF_8);
        audit.record(AuditEvent.builder("VIEW_SENSITIVE", "partners")
                .entity("partner_bank_account", accountId, partner.code())
                .detail("fields", iban == null ? "accountNumber" : "accountNumber,iban")
                .build());
        return new Revealed(accountId, number, iban);
    }

    private static String associatedData(String column, UUID id) {
        return "partners.partner_bank_accounts." + column + ":" + id;
    }

    private static UUID actor() {
        return CurrentContext.requireActor().userId();
    }
}
