package com.erp.platform.jooq;

import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Reads and sets transaction-local PostgreSQL settings ({@code set_config(…, true)}). */
@Component
public class TransactionSettings {

    private final DSLContext dsl;

    public TransactionSettings(DSLContext dsl) {
        this.dsl = dsl;
    }

    public @Nullable String get(String name) {
        return dsl.fetchSingle("SELECT current_setting(?, true)", name).get(0, String.class);
    }

    /** Sets the setting until the end of the current transaction. */
    public void set(String name, String value) {
        dsl.execute("SELECT set_config(?, ?, true)", name, value);
    }
}
