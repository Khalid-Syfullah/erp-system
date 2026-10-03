package com.erp.support;

import static com.erp.support.AuthTestSupport.unsafe;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.erp.support.AuthTestSupport.TestUser;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds organization and HR structures through the API (so every fixture passes the same rules as
 * production data) and small JSON bodies for requests.
 */
@TestComponent
public class OrgFixtures {

    /** A signed-in company and HR administrator of one company. */
    public record Admin(TestUser user, Cookie session, UUID company) {

        public String path(String suffix) {
            return "/api/v1/companies/" + company + suffix;
        }
    }

    public static final String MERGE_PATCH = "application/merge-patch+json";

    public static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final JsonMapper JSON = JSON_MAPPER;

    private final MockMvc mvc;
    private final AuthTestSupport auth;

    public OrgFixtures(MockMvc mvc, AuthTestSupport auth) {
        this.mvc = mvc;
        this.auth = auth;
    }

    /** A new company with a user holding COMPANY_ADMIN and HR_MANAGER there (MFA enrolled, signed in). */
    public Admin admin() throws Exception {
        return admin(auth.company());
    }

    public Admin admin(UUID company) throws Exception {
        TestUser user = auth.enrollMfa(auth.user());
        auth.assign(user, "COMPANY_ADMIN", company);
        auth.assign(user, "HR_MANAGER", company);
        return new Admin(user, auth.login(user), company);
    }

    public UUID department(Admin admin, String code, UUID parentId, UUID branchId) throws Exception {
        return create(
                admin,
                "/departments",
                json("code", code, "name", "Department " + code, "parentId", parentId, "branchId", branchId));
    }

    public UUID position(Admin admin, String code, UUID departmentId) throws Exception {
        return create(
                admin, "/positions", json("code", code, "title", "Position " + code, "departmentId", departmentId));
    }

    /** An employee hired on {@code hireDate} with an open-ended assignment from that date. */
    public UUID employee(Admin admin, String number, LocalDate hireDate, UUID branchId, UUID departmentId)
            throws Exception {
        return employee(admin, number, hireDate, assignment(branchId, departmentId, null, null));
    }

    public UUID employee(Admin admin, String number, LocalDate hireDate, Map<String, Object> initialAssignment)
            throws Exception {
        Map<String, Object> body = map(
                "employeeNumber", number,
                "firstName", "First" + number,
                "lastName", "Last" + number,
                "hireDate", hireDate,
                "initialAssignment", initialAssignment);
        return create(admin, "/employees", JSON.writeValueAsString(body));
    }

    public static Map<String, Object> assignment(UUID branchId, UUID departmentId, UUID positionId, UUID managerId) {
        return map(
                "branchId",
                branchId,
                "departmentId",
                departmentId,
                "positionId",
                positionId,
                "managerEmployeeId",
                managerId);
    }

    public UUID create(Admin admin, String path, String body) throws Exception {
        MvcResult result = mvc.perform(unsafe(post(admin.path(path)))
                        .cookie(admin.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError(
                    "POST " + path + " failed: " + result.getResponse().getStatus() + " "
                            + result.getResponse().getContentAsString());
        }
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    /** Marks a member that must be sent as JSON {@code null} (merge patch "clear"). */
    public static final Object NULL = new Object();

    /** A JSON object from alternating keys and values; {@code null} values are left out, {@link #NULL} is sent as null. */
    public static String json(Object... keysAndValues) {
        return JSON.writeValueAsString(map(keysAndValues));
    }

    public static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value == NULL) {
                map.put((String) keysAndValues[i], null);
            } else if (value != null) {
                map.put(
                        (String) keysAndValues[i],
                        value instanceof UUID || value instanceof LocalDate ? value.toString() : value);
            }
        }
        return map;
    }

    public static String etag(MvcResult result) {
        return result.getResponse().getHeader("ETag");
    }

    public static String etag(int version) {
        return "W/\"" + version + "\"";
    }
}
