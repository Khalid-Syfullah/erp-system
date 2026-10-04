package com.erp.hr.web;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.HrFixtures.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AuthTestSupport;
import com.erp.support.HrFixtures;
import com.erp.support.HrFixtures.Hr;
import com.erp.support.HrFixtures.Person;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Employee records (PRODUCT_SPEC.md §10.1, SECURITY.md §7): personal data, field-encrypted sensitive
 * values with masked responses and an audited reveal, the self-service user link, termination
 * (user deactivated, future leave cancelled), bank accounts and documents.
 */
class EmployeeRecordsIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HrFixtures hr;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private Hr h;
    private UUID employee;

    @BeforeEach
    void setUp() throws Exception {
        h = hr.setup();
        employee = hr.employee(h, "E001", LocalDate.of(h.today().getYear() - 2, 1, 1), null);
    }

    @Test
    void sensitiveValuesAreEncryptedMaskedAndRevealedWithAudit() throws Exception {
        patchEmployee(
                        h.session(),
                        Map.of(
                                "personalEmail", "Ann@Example.org",
                                "phone", "+1 555 0100",
                                "address", Map.of("line1", "1 Main St", "city", "Springfield", "countryCode", "US"),
                                "dateOfBirth", "1990-05-17",
                                "nationalId", "123-45-6789"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalEmail").value("ann@example.org"))
                .andExpect(jsonPath("$.address.city").value("Springfield"))
                .andExpect(jsonPath("$.nationalIdMasked").value("****6789"))
                .andExpect(jsonPath("$.dateOfBirthSet").value(true));
        String body = hr.body(h.session(), h.path("/employees/" + employee));
        assertThat(body).doesNotContain("1990-05-17").doesNotContain("123-45-6789");

        // At rest: ciphertext only.
        byte[][] stored = CurrentContext.callWith(
                RequestContext.forRequest("raw-" + UUID.randomUUID()).withCompany(h.company()),
                () -> tx.execute(s -> dsl.fetchSingle(
                                "SELECT date_of_birth_encrypted, national_id_encrypted FROM hr.employees WHERE id = ?",
                                employee)
                        .into(byte[][].class)));
        assertThat(new String(stored[0], StandardCharsets.ISO_8859_1)).doesNotContain("1990");
        assertThat(new String(stored[1], StandardCharsets.ISO_8859_1)).doesNotContain("6789");

        // Reveal (step-up: the login just happened) is audited.
        mvc.perform(unsafe(post(h.path("/employees/" + employee + "/reveal"))).cookie(h.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateOfBirth").value("1990-05-17"))
                .andExpect(jsonPath("$.nationalId").value("123-45-6789"));
        assertThat(auditActions("employee")).contains("VIEW_SENSITIVE");
        // The audit trail records that the values changed, never the values.
        assertThat(auditJson("employee")).doesNotContain("123-45-6789").doesNotContain("1990-05-17");

        // Without hr.employee.read_sensitive: no setting, no reveal.
        Cookie officer = login("hr.employee.read", "hr.employee.manage");
        patchEmployee(officer, Map.of("nationalId", "999-99-9999")).andExpect(status().isForbidden());
        patchEmployee(officer, Map.of("phone", "+1 555 0199")).andExpect(status().isOk());
        mvc.perform(unsafe(post(h.path("/employees/" + employee + "/reveal"))).cookie(officer))
                .andExpect(status().isForbidden());
        // Clearing the national ID removes it.
        patchEmployee(h.session(), map("nationalId", OrgFixtures.NULL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nationalIdMasked").doesNotExist());
    }

    @Test
    void aLinkedUserUsesSelfServiceAndIsDisabledAtTermination() throws Exception {
        Person ann = hr.person(h, employee);
        mvc.perform(get(h.path("/me/employee")).cookie(ann.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeNumber").value("E001"));
        // Ann edits her contact data, not her name or HR data.
        mvc.perform(unsafe(patch(h.path("/me/employee")))
                        .cookie(ann.session())
                        .header("If-Match", OrgFixtures.etag(hr.version(h, "/employees/" + employee)))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("phone", "+44 20 7946 0000")))
                .andExpect(status().isOk());
        mvc.perform(unsafe(patch(h.path("/me/employee")))
                        .cookie(ann.session())
                        .header("If-Match", OrgFixtures.etag(hr.version(h, "/employees/" + employee)))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("hireDate", "2000-01-01")))
                .andExpect(status().isBadRequest());
        // She cannot read other records.
        mvc.perform(get(h.path("/employees/" + employee)).cookie(ann.session())).andExpect(status().isForbidden());

        // A user is linked once per company; users outside the company cannot be linked.
        UUID other = hr.employee(h, "E002", LocalDate.of(h.today().getYear() - 1, 1, 1), null);
        linkUser(other, ann.user().id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_LINKED"));
        linkUser(other, auth.user().id()).andExpect(status().isUnprocessableContent());

        // Termination disables the user at once.
        expect(
                hr.action(
                        h.session(),
                        h.path("/employees/" + employee + "/terminate"),
                        hr.version(h, "/employees/" + employee),
                        null,
                        OrgFixtures.map("terminationDate", h.today(), "reason", "Resigned")),
                200);
        mvc.perform(get(h.path("/me/employee")).cookie(ann.session())).andExpect(status().isUnauthorized());
        String status = CurrentContext.callWith(
                RequestContext.forRequest("raw-" + UUID.randomUUID()).withGlobalAccess(),
                () -> tx.execute(s -> dsl.fetchSingle(
                                "SELECT status FROM auth.users WHERE id = ?",
                                ann.user().id())
                        .get(0, String.class)));
        assertThat(status).isEqualTo("DISABLED");
    }

    @Test
    void bankAccountsAreMaskedAndRevealedWithAudit() throws Exception {
        String path = h.path("/employees/" + employee + "/bank-accounts");
        expect(
                hr.post(
                        h.session(),
                        path,
                        OrgFixtures.map("bankName", "First", "accountHolder", "Ann", "accountNumber", "12345678")),
                201);
        String second = hr.post(
                        h.session(),
                        path,
                        OrgFixtures.map(
                                "bankName",
                                "Second",
                                "accountHolder",
                                "Ann",
                                "accountNumber",
                                "87654321",
                                "iban",
                                "GB82WEST12345698765432",
                                "primary",
                                true))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String list = hr.body(h.session(), path);
        assertThat(JsonPath.<List<String>>read(list, "$.data[*].accountNumberMasked"))
                .containsExactly("****4321", "****5678");
        assertThat(JsonPath.<List<Boolean>>read(list, "$.data[*].primary")).containsExactly(true, false);
        assertThat(list).doesNotContain("12345678").doesNotContain("GB82");
        hr.post(
                        h.session(),
                        path,
                        OrgFixtures.map(
                                "bankName", "X", "accountHolder", "Ann", "accountNumber", "12", "iban", "GB00XX"))
                .andExpect(status().isUnprocessableContent());

        String id = JsonPath.read(second, "$.id");
        mvc.perform(unsafe(post(path + "/" + id + "/reveal")).cookie(h.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("87654321"))
                .andExpect(jsonPath("$.iban").value("GB82WEST12345698765432"));
        assertThat(auditActions("employee_bank_account")).contains("CREATE", "VIEW_SENSITIVE");
        // Bank data needs hr.employee.manage_bank.
        Cookie officer = login("hr.employee.read", "hr.employee.manage");
        mvc.perform(get(path).cookie(officer)).andExpect(status().isForbidden());
        mvc.perform(unsafe(delete(path + "/" + id)).cookie(h.session())).andExpect(status().isNoContent());
    }

    @Test
    void documentsAreUploadedDownloadedAndDeleted() throws Exception {
        String path = h.path("/employees/" + employee + "/documents");
        byte[] pdf = "%PDF-1.4\n% test contract\n".getBytes(StandardCharsets.US_ASCII);
        String created = mvc.perform(
                        upload(path, new MockMultipartFile("file", "contract.pdf", "application/pdf", pdf), "CONTRACT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("contract.pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(pdf.length))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(created, "$.id");
        assertThat(JsonPath.<List<String>>read(hr.body(h.session(), path), "$.data[*].id"))
                .containsExactly(id);
        byte[] downloaded = mvc.perform(get(path + "/" + id + "/content").cookie(h.session()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("contract.pdf")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(downloaded).isEqualTo(pdf);

        // The declared type must match the content, and only allowed types are stored.
        mvc.perform(upload(
                        path,
                        new MockMultipartFile("file", "fake.pdf", "application/pdf", "MZ...".getBytes()),
                        "OTHER"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CONTENT_TYPE_MISMATCH"));
        mvc.perform(upload(
                        path,
                        new MockMultipartFile("file", "run.exe", "application/x-msdownload", new byte[] {1}),
                        "OTHER"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CONTENT_TYPE_NOT_ALLOWED"));
        mvc.perform(upload(path, new MockMultipartFile("file", "x.pdf", "application/pdf", pdf), "PASSPORT"))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(unsafe(delete(path + "/" + id)).cookie(h.session())).andExpect(status().isNoContent());
        assertThat(JsonPath.<List<?>>read(hr.body(h.session(), path), "$.data")).isEmpty();
        // Another company's employee is not reachable through this company.
        Hr other = hr.setup();
        mvc.perform(get(other.path("/employees/" + employee + "/documents")).cookie(other.session()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions patchEmployee(Cookie session, Map<String, ?> changes)
            throws Exception {
        return mvc.perform(unsafe(patch(h.path("/employees/" + employee)))
                .cookie(session)
                .header("If-Match", OrgFixtures.etag(hr.version(h, "/employees/" + employee)))
                .contentType(OrgFixtures.MERGE_PATCH)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(changes)));
    }

    private org.springframework.test.web.servlet.ResultActions linkUser(UUID employeeId, UUID userId) throws Exception {
        return mvc.perform(unsafe(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        h.path("/employees/" + employeeId + "/user")))
                .cookie(h.session())
                .header("If-Match", OrgFixtures.etag(hr.version(h, "/employees/" + employeeId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.json("userId", userId)));
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(
            String path, MockMultipartFile file, String type) {
        var request = multipart(path).file(file);
        request.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("Origin", "http://localhost")
                .param("documentType", type)
                .param("title", "Document")
                .cookie(h.session());
        return request;
    }

    private Cookie login(String... permissions) throws Exception {
        var user = auth.user();
        auth.assign(user, auth.customRole(permissions), h.company());
        auth.invalidatePermissionCache();
        return auth.login(user);
    }

    private List<String> auditActions(String entityType) {
        return CurrentContext.callWith(
                RequestContext.forRequest("audit-" + UUID.randomUUID()).withCompany(h.company()),
                () -> tx.execute(s -> dsl.fetch(
                                "SELECT action FROM admin.audit_log WHERE company_id = ? AND entity_type = ?",
                                h.company(),
                                entityType)
                        .getValues(0, String.class)));
    }

    private String auditJson(String entityType) {
        return CurrentContext.callWith(
                RequestContext.forRequest("audit-" + UUID.randomUUID()).withCompany(h.company()),
                () -> tx.execute(s -> dsl.fetch(
                                "SELECT row_to_json(a)::text FROM admin.audit_log a WHERE company_id = ? AND entity_type = ?",
                                h.company(),
                                entityType)
                        .getValues(0, String.class)
                        .toString()));
    }

    private static Map<String, Object> map(String key, Object value) {
        return OrgFixtures.map(key, value);
    }
}
