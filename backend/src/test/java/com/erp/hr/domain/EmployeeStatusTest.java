package com.erp.hr.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmployeeStatusTest {

    @Test
    void lifecycleFollowsTheSpecification() {
        assertThat(EmployeeStatus.ONBOARDING.canTransitionTo(EmployeeStatus.ACTIVE))
                .isTrue();
        assertThat(EmployeeStatus.ONBOARDING.canTransitionTo(EmployeeStatus.ON_LEAVE))
                .isFalse();
        assertThat(EmployeeStatus.ACTIVE.canTransitionTo(EmployeeStatus.ON_LEAVE))
                .isTrue();
        assertThat(EmployeeStatus.ON_LEAVE.canTransitionTo(EmployeeStatus.ACTIVE))
                .isTrue();
        assertThat(EmployeeStatus.ACTIVE.canTransitionTo(EmployeeStatus.ACTIVE)).isFalse();
        for (EmployeeStatus status : EmployeeStatus.values()) {
            assertThat(status.canTransitionTo(EmployeeStatus.TERMINATED)).isEqualTo(!status.isTerminal());
            assertThat(EmployeeStatus.TERMINATED.canTransitionTo(status)).isFalse();
        }
    }
}
