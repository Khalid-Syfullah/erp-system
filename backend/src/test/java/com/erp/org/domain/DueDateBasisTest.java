package com.erp.org.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DueDateBasisTest {

    @Test
    void documentDateBasisAddsDays() {
        assertThat(DueDateBasis.DOCUMENT_DATE.dueDate(LocalDate.of(2026, 1, 31), 30))
                .isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(DueDateBasis.DOCUMENT_DATE.dueDate(LocalDate.of(2026, 1, 31), 0))
                .isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    void endOfMonthBasisStartsAtTheLastDayOfTheMonth() {
        assertThat(DueDateBasis.END_OF_MONTH.dueDate(LocalDate.of(2026, 2, 3), 0))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(DueDateBasis.END_OF_MONTH.dueDate(LocalDate.of(2028, 2, 3), 0))
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(DueDateBasis.END_OF_MONTH.dueDate(LocalDate.of(2026, 12, 15), 10))
                .isEqualTo(LocalDate.of(2027, 1, 10));
    }

    @Test
    void negativeDaysAreRejected() {
        assertThatThrownBy(() -> DueDateBasis.DOCUMENT_DATE.dueDate(LocalDate.of(2026, 1, 1), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
