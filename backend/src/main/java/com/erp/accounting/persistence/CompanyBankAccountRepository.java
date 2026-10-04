package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.BANK_ACCOUNTS;

import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.AccountingViews;
import com.erp.db.accounting.tables.records.BankAccountsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** The company's bank and cash accounts; numbers are field-encrypted (SECURITY.md §7). */
@Repository
public class CompanyBankAccountRepository {

    private static final ListBinding BINDING = ListBinding.builder(AccountingListings.BANK_ACCOUNTS)
            .field("name", BANK_ACCOUNTS.NAME)
            .field("currencyCode", BANK_ACCOUNTS.CURRENCY_CODE)
            .field("isActive", BANK_ACCOUNTS.IS_ACTIVE)
            .tiebreaker(BANK_ACCOUNTS.ID)
            .build();

    /** The stored encrypted numbers of an account. */
    public record Secrets(byte @Nullable [] accountNumber, byte @Nullable [] iban) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public CompanyBankAccountRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID newId() {
        return dsl.fetchSingle("SELECT uuidv7()").get(0, UUID.class);
    }

    public void insert(
            UUID companyId,
            UUID id,
            String name,
            UUID accountId,
            String currencyCode,
            @Nullable String bankName,
            byte @Nullable [] number,
            byte @Nullable [] iban,
            @Nullable Short keyVersion,
            @Nullable String last4,
            UUID actor) {
        dsl.insertInto(BANK_ACCOUNTS)
                .set(BANK_ACCOUNTS.ID, id)
                .set(BANK_ACCOUNTS.COMPANY_ID, companyId)
                .set(BANK_ACCOUNTS.NAME, name)
                .set(BANK_ACCOUNTS.ACCOUNT_ID, accountId)
                .set(BANK_ACCOUNTS.CURRENCY_CODE, currencyCode)
                .set(BANK_ACCOUNTS.BANK_NAME, bankName)
                .set(BANK_ACCOUNTS.ACCOUNT_NUMBER_ENCRYPTED, number)
                .set(BANK_ACCOUNTS.IBAN_ENCRYPTED, iban)
                .set(BANK_ACCOUNTS.KEY_VERSION, keyVersion)
                .set(BANK_ACCOUNTS.ACCOUNT_NUMBER_LAST4, last4)
                .set(BANK_ACCOUNTS.CREATED_BY, actor)
                .set(BANK_ACCOUNTS.UPDATED_BY, actor)
                .execute();
    }

    public boolean update(
            UUID companyId, UUID id, int version, String name, @Nullable String bankName, boolean active, UUID actor) {
        return dsl.update(BANK_ACCOUNTS)
                        .set(BANK_ACCOUNTS.NAME, name)
                        .set(BANK_ACCOUNTS.BANK_NAME, bankName)
                        .set(BANK_ACCOUNTS.IS_ACTIVE, active)
                        .set(BANK_ACCOUNTS.UPDATED_AT, OffsetDateTime.now())
                        .set(BANK_ACCOUNTS.UPDATED_BY, actor)
                        .set(BANK_ACCOUNTS.VERSION, version + 1)
                        .where(BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                        .and(BANK_ACCOUNTS.ID.eq(id))
                        .and(BANK_ACCOUNTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public Optional<AccountingViews.BankAccount> find(UUID companyId, UUID id) {
        return dsl.selectFrom(BANK_ACCOUNTS)
                .where(BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(BANK_ACCOUNTS.ID.eq(id))
                .fetchOptional(CompanyBankAccountRepository::toView);
    }

    /** The account {@code FOR SHARE}: a deactivation waits for the payment using it. */
    public Optional<AccountingViews.BankAccount> forUse(UUID companyId, UUID id) {
        return dsl.selectFrom(BANK_ACCOUNTS)
                .where(BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(BANK_ACCOUNTS.ID.eq(id))
                .forShare()
                .fetchOptional(CompanyBankAccountRepository::toView);
    }

    public Optional<AccountingViews.BankAccount> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(BANK_ACCOUNTS)
                .where(BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(BANK_ACCOUNTS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(CompanyBankAccountRepository::toView);
    }

    public Optional<AccountingViews.BankAccount> byGlAccount(UUID companyId, UUID accountId) {
        return dsl.selectFrom(BANK_ACCOUNTS)
                .where(BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(BANK_ACCOUNTS.ACCOUNT_ID.eq(accountId))
                .fetchOptional(CompanyBankAccountRepository::toView);
    }

    public PageResponse<AccountingViews.BankAccount> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                BANK_ACCOUNTS,
                BANK_ACCOUNTS.COMPANY_ID.eq(companyId),
                query,
                BINDING,
                CompanyBankAccountRepository::toView);
    }

    static AccountingViews.BankAccount toView(BankAccountsRecord r) {
        return new AccountingViews.BankAccount(
                r.getId(),
                r.getName(),
                r.getAccountId(),
                r.getCurrencyCode(),
                r.getBankName(),
                r.getAccountNumberLast4(),
                r.getAccountNumberEncrypted() != null,
                r.getIbanEncrypted() != null,
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
