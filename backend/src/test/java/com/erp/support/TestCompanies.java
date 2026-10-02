package com.erp.support;

import static com.erp.db.org.Tables.COMPANIES;

import java.util.UUID;
import org.jooq.DSLContext;

/** Test data helpers for the org core tables. */
public final class TestCompanies {

    private TestCompanies() {}

    /** Inserts a company (org.companies is not company-scoped, so no context is needed). */
    public static UUID create(DSLContext dsl) {
        String code = "T"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return dsl.insertInto(COMPANIES)
                .set(COMPANIES.CODE, code)
                .set(COMPANIES.LEGAL_NAME, "Test company " + code)
                .set(COMPANIES.DISPLAY_NAME, code)
                .set(COMPANIES.COUNTRY_CODE, "US")
                .set(COMPANIES.BASE_CURRENCY, "USD")
                .set(COMPANIES.TIMEZONE, "America/New_York")
                .returning(COMPANIES.ID)
                .fetchOne(COMPANIES.ID);
    }
}
