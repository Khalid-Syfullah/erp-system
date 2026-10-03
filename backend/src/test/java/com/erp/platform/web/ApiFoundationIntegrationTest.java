package com.erp.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.IntegrationTest;
import com.erp.support.TestCompanies;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** API conventions of API.md and SECURITY.md exercised through the full filter and MVC stack. */
class ApiFoundationIntegrationTest extends IntegrationTest {

    private static final String BASE = "/api/v1/_test";
    private static final String VALID_ECHO = """
            {"code":"  ABC ","amount":"12.50","nested":{"name":"n"},"lines":[{"quantity":"1"}],"status":"OPEN"}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    // ------------------------------------------------------------------ security and headers

    @Test
    void publicEndpointIsReachableAnonymouslyWithSecurityHeaders() throws Exception {
        mvc.perform(get(BASE + "/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void hstsIsSentOverHttps() throws Exception {
        mvc.perform(get(BASE + "/public").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=63072000 ; includeSubDomains"));
    }

    @Test
    void protectedEndpointRequiresAuthentication() throws Exception {
        MvcResult result = expectProblem(mvc.perform(get(BASE + "/authenticated")), 401, "UNAUTHENTICATED")
                .andExpect(jsonPath("$.instance").value(BASE + "/authenticated"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains(result.getResponse().getHeader("X-Request-Id"));
    }

    @Test
    void unknownPathsAreUnauthorizedForAnonymousAndNotFoundForAuthenticated() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/nope")), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(get("/api/v1/nope").with(user("tester"))), 404, "NOT_FOUND");
    }

    @Test
    void endpointWithoutAccessAnnotationIsDenied() throws Exception {
        expectProblem(mvc.perform(get(BASE + "/unannotated").with(user("tester"))), 403, "FORBIDDEN");
    }

    @Test
    void permissionChecksFailClosedWithoutAnAuthenticatedActor() throws Exception {
        expectProblem(mvc.perform(get(BASE + "/permission/read").with(user("tester"))), 403, "FORBIDDEN");
    }

    @Test
    void unsafeMethodsRequireCsrfToken() throws Exception {
        expectProblem(
                mvc.perform(post(BASE + "/echo")
                        .with(user("tester"))
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ECHO)),
                403,
                "CSRF_INVALID");
    }

    // ------------------------------------------------------------------ request ID

    @Test
    void echoesValidRequestIdAndReplacesUnsafeOnes() throws Exception {
        mvc.perform(get(BASE + "/public").header("X-Request-Id", "client-trace-0001"))
                .andExpect(header().string("X-Request-Id", "client-trace-0001"));
        mvc.perform(get(BASE + "/public").header("X-Request-Id", "bad id\r\nInjected: yes"))
                .andExpect(header().string("X-Request-Id", not(containsString("bad"))))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern("^[0-9a-f-]{36}$")));
    }

    // ------------------------------------------------------------------ JSON and validation

    @Test
    void acceptsValidBodyAndSerializesDecimalsAsStrings() throws Exception {
        mvc.perform(authenticatedPost("/echo").content(VALID_ECHO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ABC"))
                .andExpect(jsonPath("$.amount").value("12.50"))
                .andExpect(jsonPath("$.lines[0].quantity").value("1"));
    }

    @Test
    void reportsAllBeanValidationErrorsWithJsonPointers() throws Exception {
        String body = """
                {"code":" ","amount":"-1","nested":{"name":""},"lines":[{"quantity":"1"},{"quantity":"0"}]}""";

        expectProblem(mvc.perform(authenticatedPost("/echo").content(body)), 422, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[*].pointer")
                        .value(hasItems("/code", "/amount", "/nested/name", "/lines/1/quantity")))
                .andExpect(jsonPath("$.errors[?(@.pointer=='/code')].code").value(hasItems("NOT_BLANK")))
                .andExpect(jsonPath("$.errors[?(@.pointer=='/lines/1/quantity')].code")
                        .value(hasItems("POSITIVE")));
    }

    @Test
    void rejectsUnknownPropertiesWithPointer() throws Exception {
        String body = VALID_ECHO.replace("\"status\":\"OPEN\"", "\"status\":\"OPEN\",\"isAdmin\":true");

        expectProblem(mvc.perform(authenticatedPost("/echo").content(body)), 400, "BAD_REQUEST")
                .andExpect(jsonPath("$.errors[0].pointer").value("/isAdmin"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PROPERTY"));
    }

    @Test
    void rejectsDecimalsSentAsJsonNumbers() throws Exception {
        String body = VALID_ECHO.replace("\"12.50\"", "12.50");

        expectProblem(mvc.perform(authenticatedPost("/echo").content(body)), 400, "BAD_REQUEST")
                .andExpect(jsonPath("$.errors[0].pointer").value("/amount"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("JSON string")));
    }

    @Test
    void rejectsInvalidEnumAndControlCharactersWithoutLeakingTypes() throws Exception {
        String badEnum = VALID_ECHO.replace("\"OPEN\"", "\"DELETED\"");
        String controlChar = VALID_ECHO.replace("\"n\"", "\"a\\u0000b\"");

        expectProblem(mvc.perform(authenticatedPost("/echo").content(badEnum)), 400, "BAD_REQUEST")
                .andExpect(jsonPath("$.errors[0].pointer").value("/status"))
                .andExpect(jsonPath("$.errors[0].message").value("must be one of [OPEN, CLOSED]"));
        expectProblem(mvc.perform(authenticatedPost("/echo").content(controlChar)), 400, "BAD_REQUEST")
                .andExpect(jsonPath("$.errors[0].pointer").value("/nested/name"));
        mvc.perform(authenticatedPost("/echo").content(badEnum))
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("FoundationProbeController"))));
    }

    @Test
    void rejectsMalformedJsonAndWrongContentType() throws Exception {
        expectProblem(mvc.perform(authenticatedPost("/echo").content("{\"code\":")), 400, "BAD_REQUEST");
        expectProblem(
                mvc.perform(post(BASE + "/echo")
                        .with(user("tester"))
                        .with(csrf())
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello")),
                415,
                "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void rejectsOversizedBodies() throws Exception {
        String huge = "{\"code\":\"" + "x".repeat(1_100_000) + "\"}";

        expectProblem(mvc.perform(authenticatedPost("/echo").content(huge)), 413, "PAYLOAD_TOO_LARGE");
    }

    // ------------------------------------------------------------------ routing errors

    @Test
    void wrongMethodIsMethodNotAllowedWithAllowHeader() throws Exception {
        expectProblem(mvc.perform(get(BASE + "/echo").with(user("tester"))), 405, "METHOD_NOT_ALLOWED")
                .andExpect(header().string("Allow", containsString("POST")));
    }

    @Test
    void malformedIdentifierIsNotFound() throws Exception {
        expectProblem(mvc.perform(get(BASE + "/items/not-a-uuid").with(user("tester"))), 404, "NOT_FOUND");
    }

    @Test
    void unexpectedErrorsHideInternals() throws Exception {
        expectProblem(mvc.perform(get(BASE + "/boom").with(user("tester"))), 500, "INTERNAL_ERROR")
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at com.erp"))));
    }

    // ------------------------------------------------------------------ concurrency preconditions

    @Test
    void enforcesIfMatch() throws Exception {
        expectProblem(mvc.perform(versioned(null)), 428, "PRECONDITION_REQUIRED");
        expectProblem(mvc.perform(versioned("W/\"2\"")), 412, "PRECONDITION_FAILED");
        mvc.perform(versioned("W/\"3\"")).andExpect(status().isOk()).andExpect(header().string("ETag", "W/\"4\""));
    }

    // ------------------------------------------------------------------ database errors

    @Test
    void translatesDatabaseConstraintViolations() throws Exception {
        UUID company = tx.execute(status -> TestCompanies.create(dsl));
        String path = "/companies/" + company + "/branches";

        mvc.perform(authenticatedPost(path).content("{\"code\":\"HQ\",\"name\":\"Head office\"}"))
                .andExpect(status().isOk());
        expectProblem(
                        mvc.perform(authenticatedPost(path).content("{\"code\":\"HQ\",\"name\":\"Again\"}")),
                        409,
                        "DUPLICATE_CODE")
                .andExpect(content().string(not(containsString("uq_branches"))));
        expectProblem(
                        mvc.perform(authenticatedPost(path).content("{\"code\":\"lower case!\",\"name\":\"x\"}")),
                        422,
                        "VALIDATION_FAILED")
                .andExpect(content().string(not(containsString("ck_branches"))));
        expectProblem(
                mvc.perform(authenticatedPost("/companies/" + UUID.randomUUID() + "/branches")
                        .content("{\"code\":\"X\",\"name\":\"Ghost company\"}")),
                422,
                "VALIDATION_FAILED");
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder authenticatedPost(String path) {
        return post(BASE + path)
                .with(user("tester"))
                .with(csrf())
                .header("Origin", "http://localhost")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder versioned(String ifMatch) {
        MockHttpServletRequestBuilder request =
                put(BASE + "/versioned").with(user("tester")).with(csrf()).header("Origin", "http://localhost");
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    static ResultActions expectProblem(ResultActions actions, int status, String code) throws Exception {
        return actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type")
                        .value("urn:erp:problem:" + code.toLowerCase().replace('_', '-')))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
