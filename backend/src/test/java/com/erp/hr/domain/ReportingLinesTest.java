package com.erp.hr.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportingLinesTest {

    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    private static final LocalDate MAR = LocalDate.of(2026, 3, 1);

    private final Map<UUID, List<ReportingLines.ManagerSpan>> lines = new HashMap<>();
    private final ReportingLines.ManagerLookup lookup =
            (employee, period) -> lines.getOrDefault(employee, List.of()).stream()
                    .filter(s -> s.period().overlaps(period))
                    .toList();

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private void reports(UUID employee, UUID manager, LocalDate from, LocalDate to) {
        lines.computeIfAbsent(employee, k -> new ArrayList<>())
                .add(new ReportingLines.ManagerSpan(manager, new EffectivePeriod(from, to)));
    }

    @Test
    void directAndIndirectLoopsAreCycles() {
        reports(b, a, JAN, null);
        reports(c, b, JAN, null);

        assertThat(ReportingLines.check(a, b, new EffectivePeriod(JAN, null), lookup))
                .isEqualTo(ReportingLines.Result.CYCLE);
        assertThat(ReportingLines.check(a, c, new EffectivePeriod(JAN, null), lookup))
                .isEqualTo(ReportingLines.Result.CYCLE);
        assertThat(ReportingLines.check(c, a, new EffectivePeriod(JAN, null), lookup))
                .isEqualTo(ReportingLines.Result.OK);
    }

    @Test
    void onlyOverlappingPeriodsFormALoop() {
        reports(b, a, MAR, null); // b reports to a from March

        assertThat(ReportingLines.check(a, b, new EffectivePeriod(JAN, MAR.minusDays(1)), lookup))
                .isEqualTo(ReportingLines.Result.OK);
        assertThat(ReportingLines.check(a, b, new EffectivePeriod(JAN, MAR), lookup))
                .isEqualTo(ReportingLines.Result.CYCLE);
    }

    @Test
    void chainsAreFollowedOnlyForTheirOverlappingPart() {
        // c → b only in January; b → a from March: no common date, no loop through c.
        reports(c, b, JAN, LocalDate.of(2026, 1, 31));
        reports(b, a, MAR, null);

        assertThat(ReportingLines.check(a, c, new EffectivePeriod(JAN, null), lookup))
                .isEqualTo(ReportingLines.Result.OK);
    }

    @Test
    void overlyDeepChainsAreReported() {
        UUID top = UUID.randomUUID();
        UUID current = top;
        for (int i = 0; i < ReportingLines.MAX_DEPTH + 1; i++) {
            UUID manager = UUID.randomUUID();
            reports(current, manager, JAN, null);
            current = manager;
        }

        assertThat(ReportingLines.check(UUID.randomUUID(), top, new EffectivePeriod(JAN, null), lookup))
                .isEqualTo(ReportingLines.Result.TOO_DEEP);
    }
}
