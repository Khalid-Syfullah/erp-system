package com.erp.partners.persistence;

import static com.erp.db.partners.Tables.PARTNER_BANK_ACCOUNTS;

import com.erp.db.partners.tables.records.PartnerBankAccountsRecord;
import com.erp.partners.application.PartnerViews;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Encrypted partner bank accounts. The masked view never carries the ciphertext. */
@Repository
public class BankAccountRepository {

    private final DSLContext dsl;

    public BankAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** A new row ID, needed before encrypting (the row ID is part of the associated data). */
    public UUID newId() {
        return dsl.select(org.jooq.impl.DSL.field("uuidv7()", UUID.class))
                .fetchSingle()
                .value1();
    }

    public void insert(
            UUID companyId,
            UUID partnerId,
            UUID id,
            String bankName,
            String accountHolder,
            byte[] accountNumberEncrypted,
            byte @Nullable [] ibanEncrypted,
            @Nullable String swiftBic,
            String last4,
            int keyVersion,
            @Nullable String currencyCode,
            boolean isDefault,
            UUID actor) {
        dsl.insertInto(PARTNER_BANK_ACCOUNTS)
                .set(PARTNER_BANK_ACCOUNTS.ID, id)
                .set(PARTNER_BANK_ACCOUNTS.COMPANY_ID, companyId)
                .set(PARTNER_BANK_ACCOUNTS.PARTNER_ID, partnerId)
                .set(PARTNER_BANK_ACCOUNTS.BANK_NAME, bankName)
                .set(PARTNER_BANK_ACCOUNTS.ACCOUNT_HOLDER, accountHolder)
                .set(PARTNER_BANK_ACCOUNTS.ACCOUNT_NUMBER_ENCRYPTED, accountNumberEncrypted)
                .set(PARTNER_BANK_ACCOUNTS.IBAN_ENCRYPTED, ibanEncrypted)
                .set(PARTNER_BANK_ACCOUNTS.SWIFT_BIC, swiftBic)
                .set(PARTNER_BANK_ACCOUNTS.LAST4, last4)
                .set(PARTNER_BANK_ACCOUNTS.KEY_VERSION, (short) keyVersion)
                .set(PARTNER_BANK_ACCOUNTS.CURRENCY_CODE, currencyCode)
                .set(PARTNER_BANK_ACCOUNTS.IS_DEFAULT, isDefault)
                .set(PARTNER_BANK_ACCOUNTS.CREATED_BY, actor)
                .set(PARTNER_BANK_ACCOUNTS.UPDATED_BY, actor)
                .execute();
    }

    public List<PartnerViews.BankAccount> list(UUID companyId, UUID partnerId) {
        return dsl.selectFrom(PARTNER_BANK_ACCOUNTS)
                .where(PARTNER_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(PARTNER_BANK_ACCOUNTS.PARTNER_ID.eq(partnerId))
                .orderBy(PARTNER_BANK_ACCOUNTS.CREATED_AT, PARTNER_BANK_ACCOUNTS.ID)
                .fetch(BankAccountRepository::toView);
    }

    public Optional<PartnerViews.BankAccount> find(UUID companyId, UUID partnerId, UUID id) {
        return dsl.selectFrom(PARTNER_BANK_ACCOUNTS)
                .where(PARTNER_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(PARTNER_BANK_ACCOUNTS.PARTNER_ID.eq(partnerId))
                .and(PARTNER_BANK_ACCOUNTS.ID.eq(id))
                .fetchOptional(BankAccountRepository::toView);
    }

    public Optional<PartnerViews.EncryptedBankAccount> findEncrypted(UUID companyId, UUID partnerId, UUID id) {
        return dsl.selectFrom(PARTNER_BANK_ACCOUNTS)
                .where(PARTNER_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(PARTNER_BANK_ACCOUNTS.PARTNER_ID.eq(partnerId))
                .and(PARTNER_BANK_ACCOUNTS.ID.eq(id))
                .fetchOptional(r -> new PartnerViews.EncryptedBankAccount(
                        r.getId(),
                        r.getPartnerId(),
                        r.getAccountNumberEncrypted(),
                        r.getIbanEncrypted(),
                        r.getKeyVersion()));
    }

    public void clearDefault(UUID companyId, UUID partnerId, @Nullable UUID except) {
        dsl.update(PARTNER_BANK_ACCOUNTS)
                .set(PARTNER_BANK_ACCOUNTS.IS_DEFAULT, false)
                .where(PARTNER_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                .and(PARTNER_BANK_ACCOUNTS.PARTNER_ID.eq(partnerId))
                .and(PARTNER_BANK_ACCOUNTS.IS_DEFAULT.isTrue())
                .and(except == null ? org.jooq.impl.DSL.noCondition() : PARTNER_BANK_ACCOUNTS.ID.ne(except))
                .execute();
    }

    public boolean delete(UUID companyId, UUID partnerId, UUID id) {
        return dsl.deleteFrom(PARTNER_BANK_ACCOUNTS)
                        .where(PARTNER_BANK_ACCOUNTS.COMPANY_ID.eq(companyId))
                        .and(PARTNER_BANK_ACCOUNTS.PARTNER_ID.eq(partnerId))
                        .and(PARTNER_BANK_ACCOUNTS.ID.eq(id))
                        .execute()
                == 1;
    }

    private static PartnerViews.BankAccount toView(PartnerBankAccountsRecord r) {
        return new PartnerViews.BankAccount(
                r.getId(),
                r.getPartnerId(),
                r.getBankName(),
                r.getAccountHolder(),
                r.getLast4(),
                r.getIbanEncrypted() != null,
                r.getSwiftBic(),
                r.getCurrencyCode(),
                r.getIsDefault(),
                r.getCreatedAt(),
                r.getVersion());
    }
}
