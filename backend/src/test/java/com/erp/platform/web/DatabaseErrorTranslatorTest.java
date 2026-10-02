package com.erp.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatabaseErrorTranslatorTest {

    private final DatabaseErrorTranslator translator = new DatabaseErrorTranslator(
            List.of(() -> Map.<String, ErrorCode>of("uq_things__company_id_code", PlatformErrorCode.DUPLICATE_CODE)));

    @ParameterizedTest
    @CsvSource({
        "23505, CONFLICT",
        "23502, VALIDATION_FAILED",
        "23514, VALIDATION_FAILED",
        "23P01, CONFLICT",
        "22001, VALIDATION_FAILED",
        "22P02, VALIDATION_FAILED",
        "40001, RESOURCE_BUSY",
        "40P01, RESOURCE_BUSY",
        "55P03, RESOURCE_BUSY",
        "57014, SERVICE_UNAVAILABLE",
        "08006, SERVICE_UNAVAILABLE",
        "53300, SERVICE_UNAVAILABLE",
        "42501, NOT_FOUND",
        "42P01, INTERNAL_ERROR",
    })
    void mapsSqlStates(String sqlState, PlatformErrorCode expected) {
        assertThat(translator.translate(sqlState, null, "msg").errorCode()).isEqualTo(expected);
    }

    @Test
    void distinguishesMissingReferenceFromReferencedRow() {
        assertThat(translator
                        .translate("23503", null, "insert or update on table \"x\" violates foreign key")
                        .errorCode())
                .isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
        assertThat(translator
                        .translate("23503", null, "update or delete on table \"x\" violates foreign key")
                        .errorCode())
                .isEqualTo(PlatformErrorCode.RESOURCE_IN_USE);
    }

    @Test
    void usesModuleSpecificCodeForKnownConstraint() {
        assertThat(translator
                        .translate("23505", "uq_things__company_id_code", null)
                        .errorCode())
                .isEqualTo(PlatformErrorCode.DUPLICATE_CODE);
    }

    @Test
    void neverExposesDatabaseDetails() {
        ApiException ex = translator.translate("23505", "uq_things__company_id_code", "duplicate key (code)=(X)");
        assertThat(ex.getMessage()).doesNotContain("uq_things").doesNotContain("(X)");
    }

    @Test
    void findsSqlStateInCauseChain() {
        RuntimeException wrapped = new RuntimeException(new IllegalStateException(new SQLException("boom", "40P01")));

        assertThat(translator.translate(wrapped))
                .get()
                .extracting(ApiException::errorCode)
                .isEqualTo(PlatformErrorCode.RESOURCE_BUSY);
        assertThat(translator.translate(new IllegalStateException("no sql"))).isEmpty();
    }
}
