package com.erp.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GlobalExceptionHandlerTest {

    @Test
    void convertsConstraintNamesToCodes() {
        assertThat(GlobalExceptionHandler.constraintCode("NotBlank")).isEqualTo("NOT_BLANK");
        assertThat(GlobalExceptionHandler.constraintCode("jakarta.validation.constraints.DecimalMin"))
                .isEqualTo("DECIMAL_MIN");
        assertThat(GlobalExceptionHandler.constraintCode(null)).isEqualTo("INVALID");
    }

    @Test
    void describesExpectedFormatsWithoutJavaTypeNames() {
        assertThat(GlobalExceptionHandler.expectedFormat(BigDecimal.class)).contains("JSON string");
        assertThat(GlobalExceptionHandler.expectedFormat(UUID.class)).isEqualTo("must be a UUID");
        assertThat(GlobalExceptionHandler.expectedFormat(LocalDate.class)).contains("YYYY-MM-DD");
        assertThat(GlobalExceptionHandler.expectedFormat(Thread.State.class))
                .contains("NEW")
                .doesNotContain("java");
        assertThat(GlobalExceptionHandler.expectedFormat(Object.class)).isEqualTo("has an invalid value");
    }

    @Test
    void buildsJsonPointersFromPropertyPaths() {
        assertThat(JsonPointers.fromPropertyPath("lines[2].quantity")).isEqualTo("/lines/2/quantity");
        assertThat(JsonPointers.fromPropertyPath("a/b~c")).isEqualTo("/a~1b~0c");
        assertThat(JsonPointers.fromPropertyPath("")).isEmpty();
    }
}
