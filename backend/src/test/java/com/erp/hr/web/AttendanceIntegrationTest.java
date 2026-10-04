package com.erp.hr.web;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.HrFixtures.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.HrFixtures;
import com.erp.support.HrFixtures.Hr;
import com.erp.support.HrFixtures.Person;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Basic attendance (ADR-039): HR records and summaries, self-service clock-in and clock-out. */
class AttendanceIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HrFixtures hr;

    private Hr h;
    private UUID employee;

    @BeforeEach
    void setUp() throws Exception {
        h = hr.setup();
        employee = hr.employee(h, "E001", LocalDate.of(h.today().getYear() - 1, 1, 1), null);
    }

    @Test
    void hrRecordsDaysAndSummarisesThem() throws Exception {
        LocalDate day = h.today().minusDays(7);
        record(day, Map.of("status", "PRESENT", "checkIn", day + "T08:00:00Z", "checkOut", day + "T16:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workedMinutes").value(510));
        // The record of a day is replaced, not duplicated.
        record(day, Map.of("status", "HALF_DAY", "checkIn", day + "T08:00:00Z", "checkOut", day + "T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workedMinutes").value(240));
        record(day.minusDays(1), Map.of("status", "ABSENT")).andExpect(status().isOk());
        record(day.minusDays(2), Map.of("status", "ABSENT", "checkIn", day + "T08:00:00Z"))
                .andExpect(status().isUnprocessableContent());
        record(
                        day.minusDays(3),
                        Map.of("status", "PRESENT", "checkIn", day + "T18:00:00Z", "checkOut", day + "T08:00:00Z"))
                .andExpect(status().isUnprocessableContent());
        record(h.today().plusDays(2), Map.of("status", "PRESENT")).andExpect(status().isUnprocessableContent());
        record(day, Map.of("status", "SLEEPING")).andExpect(status().isUnprocessableContent());

        String summary = hr.body(h.session(), h.path("/attendance/summary?from=" + day.minusDays(10) + "&to=" + day));
        assertThat(JsonPath.<Map<String, Integer>>read(summary, "$.employees[0].days"))
                .isEqualTo(Map.of("HALF_DAY", 1, "ABSENT", 1));
        assertThat((Integer) JsonPath.read(summary, "$.employees[0].workedMinutes"))
                .isEqualTo(240);
        assertThat(JsonPath.<List<?>>read(
                        hr.body(h.session(), h.path("/attendance?filter[employeeId]=" + employee)), "$.data"))
                .hasSize(2);
        mvc.perform(unsafe(delete(h.path("/employees/" + employee + "/attendance/" + day.minusDays(1))))
                        .cookie(h.session()))
                .andExpect(status().isNoContent());
    }

    @Test
    void employeesClockInAndOutOncePerDay() throws Exception {
        Person me = hr.person(h, employee);
        hr.action(me.session(), h.path("/me/attendance/clock-out"), -1, null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_STATE"));
        expect(hr.action(me.session(), h.path("/me/attendance/clock-in"), -1, null, null), 200);
        hr.action(me.session(), h.path("/me/attendance/clock-in"), -1, null, null)
                .andExpect(status().isConflict());
        String out = HrFixtures.expect(hr.action(me.session(), h.path("/me/attendance/clock-out"), -1, null, null), 200)
                .getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(out, "$.source")).isEqualTo("SELF");
        assertThat((Integer) JsonPath.read(out, "$.workedMinutes")).isNotNull();
        assertThat(JsonPath.<List<?>>read(hr.body(me.session(), h.path("/me/attendance")), "$.data"))
                .hasSize(1);
        // Employees do not see others' attendance or record it.
        mvc.perform(get(h.path("/attendance")).cookie(me.session())).andExpect(status().isForbidden());
    }

    private ResultActions record(LocalDate day, Map<String, String> body) throws Exception {
        return mvc.perform(unsafe(put(h.path("/employees/" + employee + "/attendance/" + day)))
                .cookie(h.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body)));
    }
}
