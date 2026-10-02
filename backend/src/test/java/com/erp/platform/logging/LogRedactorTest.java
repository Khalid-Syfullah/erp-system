package com.erp.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LogRedactorTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "password=hunter2 next|password=*** next",
                "{\"password\":\"hunter2\",\"user\":\"amy\"}|{\"password\":\"***\",\"user\":\"amy\"}",
                "appPassword: s3cr3t|appPassword: ***",
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def|Authorization: Bearer ***",
                "token erp_pat_Ab12Cd34_secretpartXYZ used|token erp_pat_*** used",
                "jdbc:postgresql://erp_app:topsecret@db:5432/erp|jdbc:postgresql://erp_app:***@db:5432/erp",
                "nationalId=123-45-6789|nationalId=***",
                "iban = DE89370400440532013000|iban = ***",
            })
    void masksSensitiveValues(String input, String expected) {
        assertThat(LogRedactor.redact(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"GET /api/v1/reference/currencies -> 200 (3 ms)", "Started ErpApplication in 1.8 seconds", ""})
    void leavesOrdinaryMessagesUntouched(String message) {
        assertThat(LogRedactor.redact(message)).isEqualTo(message);
    }
}
