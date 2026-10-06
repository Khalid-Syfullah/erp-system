package com.erp.support;

import static com.erp.support.AuthTestSupport.unsafe;

import com.erp.reporting.application.ExportJobs;
import com.erp.support.AuthTestSupport.TestUser;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Reporting through the API (API.md §17.11): users with exactly the given permissions, report runs
 * ({@code GET {c}/reports/{code}}), exports (request, the worker run, download) and CSV parsing.
 */
public class ReportingFixtures {

    private final MockMvc mvc;
    private final AuthTestSupport auth;
    private final ExportJobs jobs;

    public ReportingFixtures(MockMvc mvc, AuthTestSupport auth, ExportJobs jobs) {
        this.mvc = mvc;
        this.auth = auth;
        this.jobs = jobs;
    }

    /** A signed-in, MFA-enrolled user of the company with exactly these permissions (all branches). */
    public Cookie user(UUID company, String... permissions) throws Exception {
        return user(company, new UUID[0], permissions);
    }

    /** A signed-in user restricted to the given branches (none = all). */
    public Cookie user(UUID company, UUID[] branches, String... permissions) throws Exception {
        TestUser user = auth.enrollMfa(auth.user());
        auth.assign(user, auth.customRole(permissions), company, branches);
        auth.invalidatePermissionCache();
        return auth.login(user);
    }

    public static String path(UUID company, String suffix) {
        return "/api/v1/companies/" + company + suffix;
    }

    public ResultActions get(Cookie session, UUID company, String code, String query) throws Exception {
        return mvc.perform(
                MockMvcRequestBuilders.get(path(company, "/reports/" + code + (query.isEmpty() ? "" : "?" + query)))
                        .cookie(session));
    }

    /** The body of a successful report run. */
    public String run(Cookie session, UUID company, String code, String query) throws Exception {
        MvcResult result = get(session, company, code, query).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError(
                    code + "?" + query + ": " + result.getResponse().getStatus() + " "
                            + result.getResponse().getContentAsString());
        }
        return result.getResponse().getContentAsString();
    }

    public static List<Map<String, Object>> rows(String body) {
        return JsonPath.read(body, "$.data");
    }

    public static Map<String, Object> totals(String body) {
        return JsonPath.read(body, "$.totals");
    }

    /** A JSON decimal string (or number) as a BigDecimal without trailing zeros; null as zero. */
    public static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : plain(new BigDecimal(value.toString()));
    }

    /** Without trailing zeros and never in exponent form (60, not 6E+1), so equals compares values. */
    public static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    public static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return rows.stream()
                .map(r -> decimal(r.get(key)))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .stripTrailingZeros();
    }

    /** All rows of a report, following the cursors with the given page size. */
    public List<Map<String, Object>> allRows(Cookie session, UUID company, String code, String query, int limit)
            throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;
        do {
            String q = query + (query.isEmpty() ? "" : "&") + "limit=" + limit
                    + (cursor == null ? "" : "&cursor=" + cursor);
            String body = run(session, company, code, q);
            all.addAll(rows(body));
            cursor = JsonPath.read(body, "$.page.nextCursor");
        } while (cursor != null);
        return all;
    }

    // --------------------------------------------------------------------------------- exports

    public ResultActions requestExport(
            Cookie session, UUID company, String code, String format, Map<String, String> parameters, String key)
            throws Exception {
        var request = unsafe(MockMvcRequestBuilders.post(path(company, "/reports/" + code + "/exports")))
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(
                        OrgFixtures.map("format", format, "parameters", parameters)));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    /** Requests an export (202) and returns the job ID. */
    public UUID export(Cookie session, UUID company, String code, String format, Map<String, String> parameters)
            throws Exception {
        MvcResult result = requestExport(session, company, code, format, parameters, "export-" + UUID.randomUUID())
                .andReturn();
        if (result.getResponse().getStatus() != 202) {
            throw new AssertionError("Export request: " + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    /** Runs the export worker for the company as the scheduler would. */
    public int processExports(UUID company) {
        return jobs.processQueued(company);
    }

    public String job(Cookie session, UUID company, UUID job) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path(company, "/report-exports/" + job))
                        .cookie(session))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    public MvcResult content(Cookie session, UUID company, UUID job) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path(company, "/report-exports/" + job + "/content"))
                        .cookie(session))
                .andReturn();
    }

    /** Requests, processes and downloads an export; returns the file's bytes. */
    public byte[] exported(Cookie session, UUID company, String code, String format, Map<String, String> parameters)
            throws Exception {
        UUID job = export(session, company, code, format, parameters);
        processExports(company);
        MvcResult result = content(session, company, job);
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("Export content: " + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString() + " job " + job(session, company, job));
        }
        return result.getResponse().getContentAsByteArray();
    }

    /** RFC 4180 CSV (UTF-8 with an optional byte order mark) as rows of fields. */
    public static List<List<String>> csv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                // part of \r\n
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
