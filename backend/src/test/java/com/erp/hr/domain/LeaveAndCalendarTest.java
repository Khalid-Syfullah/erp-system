package com.erp.hr.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LeaveAndCalendarTest {

    private static final Set<DayOfWeek> WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    @Test
    void workingDaysSkipWeekendsAndHolidays() {
        LocalDate monday = LocalDate.of(2027, 3, 1);
        WorkCalendar calendar = new WorkCalendar(WEEKEND, Set.of(monday.plusDays(2)));
        assertThat(calendar.workingDays(monday, monday.plusDays(6))).isEqualTo(4);
        assertThat(calendar.isWorkingDay(monday.plusDays(5))).isFalse();
        assertThat(calendar.workingDays(monday.plusDays(1), monday)).isZero();
        assertThat(calendar.workingDates(monday, monday.plusDays(1))).containsExactly(monday, monday.plusDays(1));
        // A Friday–Saturday weekend (configurable per company).
        WorkCalendar gulf = new WorkCalendar(WorkCalendar.weekendOf(new Short[] {5, 6}), Set.of());
        assertThat(gulf.isWorkingDay(LocalDate.of(2027, 3, 7))).isTrue(); // Sunday
        assertThat(gulf.isWorkingDay(LocalDate.of(2027, 3, 5))).isFalse(); // Friday
    }

    @Test
    void annualGrantsAreProratedByHireMonthToHalfDays() {
        BigDecimal entitlement = new BigDecimal("24");
        assertThat(LeaveEntitlement.annualGrant(entitlement, LocalDate.of(2020, 5, 3), 2027))
                .contains(new BigDecimal("24.00"));
        assertThat(LeaveEntitlement.annualGrant(entitlement, LocalDate.of(2027, 7, 20), 2027))
                .contains(new BigDecimal("12.00"));
        // 20 × 5/12 = 8.33 → 8.5 (half days)
        assertThat(LeaveEntitlement.annualGrant(new BigDecimal("20"), LocalDate.of(2027, 8, 1), 2027))
                .contains(new BigDecimal("8.50"));
        assertThat(LeaveEntitlement.annualGrant(entitlement, LocalDate.of(2028, 1, 1), 2027))
                .isEmpty();
        assertThat(LeaveEntitlement.annualGrant(BigDecimal.ZERO, LocalDate.of(2020, 1, 1), 2027))
                .isEmpty();
        assertThat(LeaveEntitlement.monthlyGrant(new BigDecimal("15"))).isEqualByComparingTo("1.25");
        assertThat(LeaveEntitlement.monthDue(LocalDate.of(2027, 3, 31), 2027, 3))
                .isTrue();
        assertThat(LeaveEntitlement.monthDue(LocalDate.of(2027, 4, 1), 2027, 3)).isFalse();
    }

    @Test
    void carryForwardIsCappedAndTheRestExpires() {
        var cf = LeaveEntitlement.carryForward(new BigDecimal("14"), new BigDecimal("5"));
        assertThat(cf.carried()).isEqualByComparingTo("5");
        assertThat(cf.expired()).isEqualByComparingTo("9");
        var all = LeaveEntitlement.carryForward(new BigDecimal("3"), new BigDecimal("5"));
        assertThat(all.carried()).isEqualByComparingTo("3");
        assertThat(all.expired()).isEqualByComparingTo("0");
        var negative = LeaveEntitlement.carryForward(new BigDecimal("-2"), new BigDecimal("5"));
        assertThat(negative.carried()).isEqualByComparingTo("0");
    }

    @Test
    void leaveRequestsFollowTheirLifecycle() {
        assertThat(LeaveRequestStatus.DRAFT.apply(LeaveRequestStatus.Action.SUBMIT))
                .isEqualTo(LeaveRequestStatus.SUBMITTED);
        assertThat(LeaveRequestStatus.SUBMITTED.apply(LeaveRequestStatus.Action.APPROVE))
                .isEqualTo(LeaveRequestStatus.APPROVED);
        assertThat(LeaveRequestStatus.SUBMITTED.apply(LeaveRequestStatus.Action.REJECT))
                .isEqualTo(LeaveRequestStatus.REJECTED);
        assertThat(LeaveRequestStatus.APPROVED.apply(LeaveRequestStatus.Action.CANCEL))
                .isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThat(LeaveRequestStatus.DRAFT.allows(LeaveRequestStatus.Action.APPROVE))
                .isFalse();
        assertThat(LeaveRequestStatus.APPROVED.allows(LeaveRequestStatus.Action.EDIT))
                .isFalse();
        assertThat(LeaveRequestStatus.REJECTED.allows(LeaveRequestStatus.Action.CANCEL))
                .isFalse();
        assertThatThrownBy(() -> LeaveRequestStatus.CANCELLED.apply(LeaveRequestStatus.Action.SUBMIT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void attendanceMinutesFollowTheTimes() {
        OffsetDateTime in = OffsetDateTime.of(2027, 3, 1, 8, 0, 0, 0, ZoneOffset.UTC);
        assertThat(AttendanceStatus.workedMinutes(in, in.plusHours(8).plusMinutes(15)))
                .isEqualTo(495);
        assertThat(AttendanceStatus.workedMinutes(in, null)).isNull();
        assertThatThrownBy(() -> AttendanceStatus.workedMinutes(in, in.minusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AttendanceStatus.PRESENT.worked()).isTrue();
        assertThat(AttendanceStatus.ABSENT.worked()).isFalse();
    }
}
