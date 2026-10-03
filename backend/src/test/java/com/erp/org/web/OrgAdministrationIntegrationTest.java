package com.erp.org.web;

import static com.erp.db.admin.Tables.AUDIT_LOG;
import static com.erp.support.AuthTestSupport.unsafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** Company and branch administration (API.md §17.2–§17.3): validation, If-Match, state and audit. */
class OrgAdministrationIntegrationTest extends IntegrationTest {

    private static final String MERGE_PATCH = "application/merge-patch+json";

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Test
    void systemAdministratorsCreateCompaniesButNeedAnAssignmentToUseThem() throws Exception {
        TestUser admin = auth.systemAdmin();
        Cookie session = auth.login(admin);
        String code = "C" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String body = mvc.perform(unsafe(post("/api/v1/companies"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(company(code, "DE", "EUR", "Europe/Berlin")))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "W/\"0\""))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.fiscalYearStartMonth").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(auditActions(id)).contains("CREATE");
        mvc.perform(get("/api/v1/admin/companies").cookie(session).param("filter[code]", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(containsInAnyOrder(id.toString())));
        // Creating a company does not grant access to its data.
        mvc.perform(get("/api/v1/companies/" + id).cookie(session)).andExpect(status().isNotFound());
        mvc.perform(unsafe(post("/api/v1/companies"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(company(code, "DE", "EUR", "Europe/Berlin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
    }

    @Test
    void companyReferenceDataIsValidated() throws Exception {
        Cookie session = auth.login(auth.systemAdmin());

        mvc.perform(unsafe(post("/api/v1/companies"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(company("BADREF", "XX", "XXX", "Mars/Olympus")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].pointer")
                        .value(containsInAnyOrder("/countryCode", "/baseCurrency", "/timezone")));
    }

    @Test
    void companyAdministratorsPatchTheirCompanyWithIfMatch() throws Exception {
        UUID company = auth.company();
        TestUser admin = auth.enrollMfa(auth.user());
        auth.assign(admin, "COMPANY_ADMIN", company);
        Cookie session = auth.login(admin);
        String path = "/api/v1/companies/" + company;

        String etag = mvc.perform(get(path).cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("ETag");
        mvc.perform(unsafe(patch(path)).cookie(session).contentType(MERGE_PATCH).content("{\"displayName\":\"x\"}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(unsafe(patch(path))
                        .cookie(session)
                        .header("If-Match", "W/\"999\"")
                        .contentType(MERGE_PATCH)
                        .content("{\"displayName\":\"x\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(unsafe(patch(path))
                        .cookie(session)
                        .header("If-Match", etag)
                        .contentType(MERGE_PATCH)
                        .content("{\"code\":\"RENAMED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(unsafe(patch(path))
                        .cookie(session)
                        .header("If-Match", etag)
                        .contentType(MERGE_PATCH)
                        .content("{\"legalName\":null,\"timezone\":\"Nowhere/Land\",\"fiscalYearStartMonth\":13,"
                                + "\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].pointer")
                        .value(containsInAnyOrder("/legalName", "/timezone", "/fiscalYearStartMonth", "/status")));

        mvc.perform(unsafe(patch(path))
                        .cookie(session)
                        .header("If-Match", etag)
                        .contentType(MERGE_PATCH)
                        .content("{\"displayName\":\"  Renamed Co  \",\"city\":\"Lyon\",\"fiscalYearStartMonth\":4,"
                                + "\"addressLine2\":null}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "W/\"1\""))
                .andExpect(jsonPath("$.displayName").value("Renamed Co"))
                .andExpect(jsonPath("$.city").value("Lyon"))
                .andExpect(jsonPath("$.fiscalYearStartMonth").value(4))
                .andExpect(jsonPath("$.code").isNotEmpty());
        assertThat(auditActions(company)).contains("UPDATE");
    }

    @Test
    void branchLifecycle() throws Exception {
        UUID company = auth.company();
        TestUser admin = auth.enrollMfa(auth.user());
        auth.assign(admin, "COMPANY_ADMIN", company);
        Cookie session = auth.login(admin);
        String branches = "/api/v1/companies/" + company + "/branches";

        String body = mvc.perform(unsafe(post(branches))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HQ\",\"name\":\"Head office\",\"countryCode\":\"FR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isActive").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String branch = branches + "/" + JsonPath.read(body, "$.id");
        mvc.perform(unsafe(post(branches))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HQ\",\"name\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        mvc.perform(unsafe(post(branches))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"XX\",\"name\":\"Nowhere\",\"countryCode\":\"QQ\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(get(branches).cookie(session))
                .andExpect(jsonPath("$.data[*].code").value(hasItem("HQ")));
        mvc.perform(unsafe(patch(branch))
                        .cookie(session)
                        .header("If-Match", "W/\"0\"")
                        .contentType(MERGE_PATCH)
                        .content("{\"countryCode\":\"QQ\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(patch(branch))
                        .cookie(session)
                        .header("If-Match", "W/\"0\"")
                        .contentType(MERGE_PATCH)
                        .content("{\"name\":\"Headquarters\",\"city\":\"Paris\",\"countryCode\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Headquarters"))
                .andExpect(jsonPath("$.countryCode").doesNotExist());

        mvc.perform(unsafe(post(branch + "/deactivate")).cookie(session).header("If-Match", "W/\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
        mvc.perform(unsafe(post(branch + "/deactivate")).cookie(session).header("If-Match", "W/\"2\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(unsafe(post(branch + "/activate")).cookie(session).header("If-Match", "W/\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true));
        mvc.perform(get(branch).cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "W/\"3\""));
        assertThat(auditActions(company)).contains("CREATE", "UPDATE", "STATE_CHANGE");
    }

    private static String company(String code, String country, String currency, String zone) {
        return "{\"code\":\"" + code + "\",\"legalName\":\"" + code + " GmbH\",\"displayName\":\"" + code
                + "\",\"countryCode\":\"" + country + "\",\"baseCurrency\":\"" + currency + "\",\"timezone\":\""
                + zone + "\"}";
    }

    private List<String> auditActions(UUID companyId) {
        return CurrentContext.callWith(
                RequestContext.forRequest("test-" + UUID.randomUUID()).withGlobalAccess(),
                () -> tx.execute(status -> dsl.select(AUDIT_LOG.ACTION)
                        .from(AUDIT_LOG)
                        .where(AUDIT_LOG.COMPANY_ID.eq(companyId))
                        .fetch(AUDIT_LOG.ACTION)));
    }
}
