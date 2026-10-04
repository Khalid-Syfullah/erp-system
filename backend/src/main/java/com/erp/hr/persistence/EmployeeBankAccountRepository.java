package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.EMPLOYEE_BANK_ACCOUNTS;

import com.erp.db.hr.tables.records.EmployeeBankAccountsRecord;
import com.erp.hr.application.HrViews;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Employee bank accounts with field-encrypted numbers (DATABASE.md §5.9). */
@Repository
public class EmployeeBankAccountRepository {

    private final DSLContext dsl;

    public EmployeeBankAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID newId() {
        return dsl.select(org.jooq.impl.DSL.field("uuidv7()", UUID.class))
                .fetchSingle()
                .value1();
    }

    public UUID insert(
            UUID companyId,
            UUID employeeId,
            UUID id,
            String bankName,
            String accountHolder,
            byte[] accountNumber,
            byte @Nullable [] iban,
            @Nullable String swiftBic,
            String last4,
            int keyVersion,
            boolean primary,
            UUID actor) {
        return dsl.insertInto(EMPLOYEE_BANK_ACCOUNTS)
                .set(EMPLOYEE_BANK_ACCOUNTS.ID, id)
                .set(EMPLOYEE_BANK_ACCOUNTS.COMPANY_ID, companyId)
                .set(EMPLOYEE_BANK_ACCOUNTS.EMPLOYEE_ID, employeeId)
                .set(EMPLOYEE_BANK_ACCOUNTS.BANK_NAME, bankName)
                .set(EMPLOYEE_BANK_ACCOUNTS.ACCOUNT_HOLDER, accountHolder)
                .set(EMPLOYEE_BANK_ACCOUNTS.ACCOUNT_NUMBER_ENCRYPTED, accountNumber)
                .set(EMPLOYEE_BANK_ACCOUNTS.IBAN_ENCRYPTED, iban)
                .set(EMPLOYEE_BANK_ACCOUNTS.SWIFT_BIC, swiftBic)
                .set(EMPLOYEE_BANK_ACCOUNTS.LAST4, last4)
                .set(EMPLOYEE_BANK_ACCOUNTS.KEY_VERSION, (short) keyVersion)
                .set(EMPLOYEE_BANK_ACCOUNTS.IS_PRIMARY, primary)
                .set(EMPLOYEE_BANK_ACCOUNTS.CREATED_BY, actor)
                .set(EMPLOYEE_BANK_ACCOUNTS.UPDATED_BY, actor)
                .returning(EMPLOYEE_BANK_ACCOUNTS.ID)
                .fetchSingle(EMPLOYEE_BANK_ACCOUNTS.ID);
    }

    public List<HrViews.BankAccount> list(UUID companyId, UUID employeeId) {
        return dsl.selectFrom(EMPLOYEE_BANK_ACCOUNTS)
                .where(EMPLOYEE_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_BANK_ACCOUNTS.EMPLOYEE_ID.eq(employeeId))
                .orderBy(EMPLOYEE_BANK_ACCOUNTS.IS_PRIMARY.desc(), EMPLOYEE_BANK_ACCOUNTS.CREATED_AT)
                .fetch(EmployeeBankAccountRepository::toView);
    }

    public Optional<HrViews.BankAccount> find(UUID companyId, UUID employeeId, UUID id) {
        return dsl.selectFrom(EMPLOYEE_BANK_ACCOUNTS)
                .where(EMPLOYEE_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_BANK_ACCOUNTS.EMPLOYEE_ID.eq(employeeId))
                .and(EMPLOYEE_BANK_ACCOUNTS.ID.eq(id))
                .fetchOptional(EmployeeBankAccountRepository::toView);
    }

    public Optional<HrViews.EncryptedBankAccount> findEncrypted(UUID companyId, UUID employeeId, UUID id) {
        return dsl.selectFrom(EMPLOYEE_BANK_ACCOUNTS)
                .where(EMPLOYEE_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_BANK_ACCOUNTS.EMPLOYEE_ID.eq(employeeId))
                .and(EMPLOYEE_BANK_ACCOUNTS.ID.eq(id))
                .fetchOptional(EmployeeBankAccountRepository::toEncrypted);
    }

    public List<HrViews.EncryptedBankAccount> primaryEncrypted(UUID companyId, Collection<UUID> employeeIds) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(EMPLOYEE_BANK_ACCOUNTS)
                .where(EMPLOYEE_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_BANK_ACCOUNTS.EMPLOYEE_ID.in(employeeIds))
                .and(EMPLOYEE_BANK_ACCOUNTS.IS_PRIMARY.isTrue())
                .fetch(EmployeeBankAccountRepository::toEncrypted);
    }

    public void clearPrimary(UUID companyId, UUID employeeId, UUID actor) {
        dsl.update(EMPLOYEE_BANK_ACCOUNTS)
                .set(EMPLOYEE_BANK_ACCOUNTS.IS_PRIMARY, false)
                .set(EMPLOYEE_BANK_ACCOUNTS.UPDATED_AT, OffsetDateTime.now())
                .set(EMPLOYEE_BANK_ACCOUNTS.UPDATED_BY, actor)
                .set(EMPLOYEE_BANK_ACCOUNTS.VERSION, EMPLOYEE_BANK_ACCOUNTS.VERSION.plus(1))
                .where(EMPLOYEE_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_BANK_ACCOUNTS.EMPLOYEE_ID.eq(employeeId))
                .and(EMPLOYEE_BANK_ACCOUNTS.IS_PRIMARY.isTrue())
                .execute();
    }

    public void makePrimary(UUID companyId, UUID id, UUID actor) {
        dsl.update(EMPLOYEE_BANK_ACCOUNTS)
                .set(EMPLOYEE_BANK_ACCOUNTS.IS_PRIMARY, true)
                .set(EMPLOYEE_BANK_ACCOUNTS.UPDATED_AT, OffsetDateTime.now())
                .set(EMPLOYEE_BANK_ACCOUNTS.UPDATED_BY, actor)
                .set(EMPLOYEE_BANK_ACCOUNTS.VERSION, EMPLOYEE_BANK_ACCOUNTS.VERSION.plus(1))
                .where(EMPLOYEE_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_BANK_ACCOUNTS.ID.eq(id))
                .execute();
    }

    public void delete(UUID companyId, UUID id) {
        dsl.deleteFrom(EMPLOYEE_BANK_ACCOUNTS)
                .where(EMPLOYEE_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_BANK_ACCOUNTS.ID.eq(id))
                .execute();
    }

    private static HrViews.BankAccount toView(EmployeeBankAccountsRecord r) {
        return new HrViews.BankAccount(
                r.getId(),
                r.getEmployeeId(),
                r.getBankName(),
                r.getAccountHolder(),
                r.getLast4(),
                r.getSwiftBic(),
                r.getIbanEncrypted() != null,
                r.getIsPrimary(),
                r.getCreatedAt(),
                r.getVersion());
    }

    private static HrViews.EncryptedBankAccount toEncrypted(EmployeeBankAccountsRecord r) {
        return new HrViews.EncryptedBankAccount(
                r.getId(),
                r.getEmployeeId(),
                r.getBankName(),
                r.getAccountHolder(),
                r.getAccountNumberEncrypted(),
                r.getIbanEncrypted(),
                r.getSwiftBic(),
                r.getIsPrimary());
    }
}
