package com.erp.partners.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.platform.banking.BankAccountNumbers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartnersDomainTest {

    @Test
    void partnerStatusTransitions() {
        assertThat(PartnerStatus.ACTIVE.apply(PartnerStatus.Action.DEACTIVATE)).isEqualTo(PartnerStatus.INACTIVE);
        assertThat(PartnerStatus.INACTIVE.apply(PartnerStatus.Action.BLOCK)).isEqualTo(PartnerStatus.BLOCKED);
        assertThat(PartnerStatus.BLOCKED.apply(PartnerStatus.Action.ACTIVATE)).isEqualTo(PartnerStatus.ACTIVE);
        assertThat(PartnerStatus.BLOCKED.allows(PartnerStatus.Action.DEACTIVATE))
                .isFalse();
        assertThatThrownBy(() -> PartnerStatus.ACTIVE.apply(PartnerStatus.Action.ACTIVATE))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DE89370400440532013000", "GB82WEST12345698765432", "NL91ABNA0417164300"})
    void validIbans(String iban) {
        assertThat(BankAccountNumbers.isValidIban(iban)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DE89370400440532013001", "XX00", "DE8937040044053201300!"})
    void invalidIbans(String iban) {
        assertThat(BankAccountNumbers.isValidIban(iban)).isFalse();
    }

    @Test
    void accountNumbersAreNormalizedAndMasked() {
        String normalized = BankAccountNumbers.normalize(" de89 3704-0044 ");
        assertThat(normalized).isEqualTo("DE8937040044");
        assertThat(BankAccountNumbers.normalize(null)).isNull();
        assertThat(BankAccountNumbers.isValidAccountNumber("123")).isFalse();
        assertThat(BankAccountNumbers.isValidAccountNumber("12345678")).isTrue();
        assertThat(BankAccountNumbers.masked(BankAccountNumbers.last4(normalized)))
                .isEqualTo("****0044");
    }
}
