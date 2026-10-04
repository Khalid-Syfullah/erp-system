package com.erp.hr.application;

import com.erp.hr.persistence.EmployeeBankAccountRepository;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.banking.BankAccountNumbers;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.crypto.FieldEncryptor;
import com.erp.platform.security.ReauthenticationGuard;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee bank accounts (PRODUCT_SPEC.md §10.1, SECURITY.md §7): numbers are field-encrypted,
 * listed masked, and revealed only after a step-up with a {@code VIEW_SENSITIVE} audit record. The
 * first account is primary; payroll pays the primary one.
 */
@Service
public class EmployeeBankAccountService {

    /** The decrypted numbers. */
    public record Revealed(
            UUID id, String accountNumber, @Nullable String iban) {}

    private final EmployeeBankAccountRepository accounts;
    private final EmployeeRepository employees;
    private final FieldEncryptor encryptor;
    private final HrContext context;
    private final AuditPort audit;

    EmployeeBankAccountService(
            EmployeeBankAccountRepository accounts,
            EmployeeRepository employees,
            FieldEncryptor encryptor,
            HrContext context,
            AuditPort audit) {
        this.accounts = accounts;
        this.employees = employees;
        this.encryptor = encryptor;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<HrViews.BankAccount> list(UUID employeeId) {
        employee(employeeId);
        return accounts.list(context.companyId(), employeeId);
    }

    @Transactional
    public HrViews.BankAccount create(UUID employeeId, HrCommands.BankAccount command) {
        UUID companyId = context.companyId();
        EmployeeView employee = employees
                .lockForChange(companyId, employeeId, CurrentContext.require().branchScope(), context.today())
                .orElseThrow(ApiException::notFound);
        String number = java.util.Objects.requireNonNull(BankAccountNumbers.normalize(command.accountNumber()));
        String iban = command.iban() == null || command.iban().isBlank()
                ? null
                : BankAccountNumbers.normalize(command.iban());
        List<FieldViolation> violations = new ArrayList<>();
        if (!BankAccountNumbers.isValidAccountNumber(number)) {
            violations.add(
                    FieldViolation.atPointer("/accountNumber", "INVALID_VALUE", "must be 4 to 34 letters or digits"));
        }
        if (iban != null && !BankAccountNumbers.isValidIban(iban)) {
            violations.add(FieldViolation.atPointer("/iban", "INVALID_IBAN", "is not a valid IBAN"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The bank account is invalid.", violations);
        }
        boolean primary =
                command.primary() || accounts.list(companyId, employeeId).isEmpty();
        if (primary) {
            accounts.clearPrimary(companyId, employeeId, context.actor());
        }
        // The row ID is part of the associated data, so it is taken before the insert.
        UUID id = accounts.newId();
        accounts.insert(
                companyId,
                employeeId,
                id,
                command.bankName().strip(),
                command.accountHolder().strip(),
                encryptor.encrypt(number.getBytes(StandardCharsets.UTF_8), associatedData("account_number", id)),
                iban == null
                        ? null
                        : encryptor.encrypt(iban.getBytes(StandardCharsets.UTF_8), associatedData("iban", id)),
                command.swiftBic(),
                BankAccountNumbers.last4(number),
                encryptor.activeKeyVersion(),
                primary,
                context.actor());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("employee_bank_account", id, employee.employeeNumber())
                .detail("bankName", command.bankName().strip())
                .detail("last4", BankAccountNumbers.last4(number))
                .detail("primary", primary)
                .redactedChange("accountNumber")
                .redactedChange("iban")
                .build());
        return accounts.find(companyId, employeeId, id).orElseThrow();
    }

    /** Makes the account the primary one (payroll pays it). */
    @Transactional
    public HrViews.BankAccount makePrimary(UUID employeeId, UUID accountId) {
        UUID companyId = context.companyId();
        EmployeeView employee = employees
                .lockForChange(companyId, employeeId, CurrentContext.require().branchScope(), context.today())
                .orElseThrow(ApiException::notFound);
        HrViews.BankAccount account =
                accounts.find(companyId, employeeId, accountId).orElseThrow(ApiException::notFound);
        if (!account.primary()) {
            accounts.clearPrimary(companyId, employeeId, context.actor());
            accounts.makePrimary(companyId, accountId, context.actor());
            audit.record(AuditEvent.builder("UPDATE", "hr")
                    .entity("employee_bank_account", accountId, employee.employeeNumber())
                    .change("primary", false, true)
                    .build());
        }
        return accounts.find(companyId, employeeId, accountId).orElseThrow();
    }

    @Transactional
    public void delete(UUID employeeId, UUID accountId) {
        UUID companyId = context.companyId();
        EmployeeView employee = employees
                .lockForChange(companyId, employeeId, CurrentContext.require().branchScope(), context.today())
                .orElseThrow(ApiException::notFound);
        HrViews.BankAccount account =
                accounts.find(companyId, employeeId, accountId).orElseThrow(ApiException::notFound);
        accounts.delete(companyId, accountId);
        audit.record(AuditEvent.builder("DELETE", "hr")
                .entity("employee_bank_account", accountId, employee.employeeNumber())
                .detail("last4", account.last4())
                .redactedChange("accountNumber")
                .build());
    }

    /** Decrypts the numbers after a recent password confirmation; audited. */
    @Transactional
    public Revealed reveal(UUID employeeId, UUID accountId) {
        ReauthenticationGuard.require(
                CurrentContext.requireActor(), context.clock().instant());
        UUID companyId = context.companyId();
        EmployeeView employee = employee(employeeId);
        HrViews.EncryptedBankAccount stored =
                accounts.findEncrypted(companyId, employeeId, accountId).orElseThrow(ApiException::notFound);
        Revealed revealed = decrypt(stored);
        audit.record(AuditEvent.builder("VIEW_SENSITIVE", "hr")
                .entity("employee_bank_account", accountId, employee.employeeNumber())
                .detail("fields", revealed.iban() == null ? "accountNumber" : "accountNumber,iban")
                .build());
        return revealed;
    }

    /** Decrypts a stored account (payroll bank file; the caller audits). */
    Revealed decrypt(HrViews.EncryptedBankAccount stored) {
        String number = new String(
                encryptor.decrypt(stored.accountNumber(), associatedData("account_number", stored.id())),
                StandardCharsets.UTF_8);
        String iban = stored.iban() == null
                ? null
                : new String(
                        encryptor.decrypt(stored.iban(), associatedData("iban", stored.id())), StandardCharsets.UTF_8);
        return new Revealed(stored.id(), number, iban);
    }

    private EmployeeView employee(UUID employeeId) {
        return employees
                .find(context.companyId(), employeeId, CurrentContext.require().branchScope(), context.today())
                .orElseThrow(ApiException::notFound);
    }

    private static String associatedData(String column, UUID id) {
        return "hr.employee_bank_accounts." + column + ":" + id;
    }
}
