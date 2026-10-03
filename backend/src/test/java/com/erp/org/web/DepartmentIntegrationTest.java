package com.erp.org.web;

import static com.erp.db.admin.Tables.AUDIT_LOG;
import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.OrgFixtures.Admin;
import com.erp.support.TestDatabase;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Departments: hierarchy, consistency rules, isolation, list features and audit (API.md §17.3). */
class DepartmentIntegrationTest extends IntegrationTest {

    private static final String MERGE_PATCH = "application/merge-patch+json";

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    OrgFixtures fixtures;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Test
    void createReadUpdateAndTheTree() throws Exception {
        Admin admin = fixtures.admin();
        UUID branch = auth.branch(admin.company(), "HQ");
        UUID root = fixtures.department(admin, "OPS", null, null);
        UUID child = fixtures.department(admin, "OPS-WH", root, branch);
        fixtures.department(admin, "OPS-QA", root, null);

        mvc.perform(get(admin.path("/departments/" + child)).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(root.toString()))
                .andExpect(jsonPath("$.branchId").value(branch.toString()))
                .andExpect(jsonPath("$.isActive").value(true));
        patchDepartment(admin, child, 0, json("name", "  Warehouse operations  ", "branchId", OrgFixtures.NULL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Warehouse operations"))
                .andExpect(jsonPath("$.branchId").doesNotExist())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get(admin.path("/departments/tree")).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("OPS"))
                .andExpect(jsonPath("$.data[0].children[*].code").value(contains("OPS-QA", "OPS-WH")));
        assertThat(auditActions(admin.company(), child)).containsExactly("CREATE", "UPDATE");
    }

    @Test
    void cyclesAreRejectedByTheServiceAndByTheDatabase() throws Exception {
        Admin admin = fixtures.admin();
        UUID a = fixtures.department(admin, "A", null, null);
        UUID b = fixtures.department(admin, "B", a, null);
        UUID c = fixtures.department(admin, "C", b, null);

        patchDepartment(admin, a, 0, json("parentId", c.toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
        patchDepartment(admin, a, 0, json("parentId", a.toString())).andExpect(status().isUnprocessableContent());
        // The trigger stops a cycle even if application code forgot to check.
        assertThatThrownBy(() -> inCompany(
                        admin.company(),
                        () -> dsl.execute("UPDATE org.departments SET parent_id = ? WHERE id = ?", c, a)))
                .hasMessageContaining("cannot be placed below its own descendant");
        // Moving a subtree elsewhere is fine.
        patchDepartment(admin, c, 0, json("parentId", OrgFixtures.NULL)).andExpect(status().isOk());
        patchDepartment(admin, a, 0, json("parentId", c.toString())).andExpect(status().isOk());
    }

    /**
     * Two moves that are each valid alone but form a cycle together. They serialize on the row locks
     * (the moved department and its new parent) and on the trigger's advisory lock, so the second sees
     * the first and fails; neither order can leave a cycle.
     */
    @Test
    void concurrentMovesCannotFormACycle() throws Exception {
        Admin admin = fixtures.admin();
        UUID a = fixtures.department(admin, "A", null, null);
        UUID b = fixtures.department(admin, "B", null, null);

        List<MockHttpServletResponse> responses;
        try (Connection holder = TestDatabase.connectAs("erp_app", TestDatabase.APP_PASSWORD)) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('org.departments'), hashtext(?))")) {
                lock.setString(1, admin.company().toString());
                lock.execute();
            }
            var first =
                    CompletableFuture.supplyAsync(() -> response(patchDepartment(admin, a, 0, json("parentId", b))));
            var second =
                    CompletableFuture.supplyAsync(() -> response(patchDepartment(admin, b, 0, json("parentId", a))));
            awaitLockWaiters(2, first, second);
            holder.commit();
            responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        // One move wins; the other sees the cycle (422) or lost a deadlock between the two (409, retryable).
        assertThat(responses).extracting(MockHttpServletResponse::getStatus).contains(200);
        assertThat(responses)
                .extracting(MockHttpServletResponse::getStatus)
                .filteredOn(code -> code != 200)
                .singleElement()
                .isIn(409, 422);
        UUID parentOfA = (UUID) parentOf(admin.company(), a);
        UUID parentOfB = (UUID) parentOf(admin.company(), b);
        assertThat(b.equals(parentOfA) && a.equals(parentOfB)).isFalse();
    }

    @Test
    void parentsAndBranchesMustBeActiveAndOfTheSameCompany() throws Exception {
        Admin admin = fixtures.admin();
        Admin other = fixtures.admin();
        UUID foreignDepartment = fixtures.department(other, "FOREIGN", null, null);
        UUID foreignBranch = auth.branch(other.company(), "FB");
        UUID parent = fixtures.department(admin, "PARENT", null, null);
        setActive(admin, parent, 0, false).andExpect(status().isOk());

        create(admin, json("code", "X1", "name", "x", "parentId", foreignDepartment, "branchId", foreignBranch))
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.errors[*].code").value(containsInAnyOrder("UNKNOWN_DEPARTMENT", "UNKNOWN_BRANCH")));
        create(admin, json("code", "X2", "name", "x", "parentId", parent))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        create(admin, json("code", "x lower", "name", "x")).andExpect(status().isUnprocessableContent());
        create(admin, json("code", "PARENT", "name", "Duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        // The same code in another company is fine.
        create(other, json("code", "PARENT", "name", "Elsewhere")).andExpect(status().isCreated());
        // Another company's department is invisible through this company's path.
        mvc.perform(get(admin.path("/departments/" + foreignDepartment)).cookie(admin.session()))
                .andExpect(status().isNotFound());
        patchDepartment(admin, foreignDepartment, 0, json("name", "Hijacked")).andExpect(status().isNotFound());
    }

    @Test
    void deactivationRespectsTheHierarchyAndBranches() throws Exception {
        Admin admin = fixtures.admin();
        UUID branch = auth.branch(admin.company(), "B1");
        UUID parent = fixtures.department(admin, "P", null, branch);
        UUID child = fixtures.department(admin, "C", parent, null);

        setActive(admin, parent, 0, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));
        // A branch with active departments cannot be deactivated either.
        mvc.perform(unsafe(post(admin.path("/branches/" + branch + "/deactivate")))
                        .cookie(admin.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("P")));

        setActive(admin, child, 0, false).andExpect(status().isOk());
        setActive(admin, parent, 0, false).andExpect(status().isOk());
        setActive(admin, child, 1, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        setActive(admin, parent, 1, false).andExpect(status().isConflict());
        setActive(admin, parent, 1, true).andExpect(status().isOk());
        setActive(admin, child, 1, true).andExpect(status().isOk());
        assertThat(auditActions(admin.company(), parent)).contains("STATE_CHANGE");
    }

    @Test
    void listsSupportFiltersSearchSortingAndCursorPagination() throws Exception {
        Admin admin = fixtures.admin();
        UUID root = fixtures.department(admin, "D1", null, null);
        fixtures.department(admin, "D2", root, null);
        fixtures.department(admin, "D3", root, null);
        UUID inactive = fixtures.department(admin, "D4", null, null);
        setActive(admin, inactive, 0, false).andExpect(status().isOk());

        String first = mvc.perform(get(admin.path("/departments"))
                        .cookie(admin.session())
                        .param("limit", "2")
                        .param("includeTotal", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(contains("D1", "D2")))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andExpect(jsonPath("$.meta.totalCount").value(4))
                .andReturn()
                .getResponse()
                .getContentAsString();
        mvc.perform(get(admin.path("/departments"))
                        .cookie(admin.session())
                        .param("limit", "2")
                        .param("includeTotal", "true")
                        .param("cursor", JsonPath.<String>read(first, "$.page.nextCursor")))
                .andExpect(jsonPath("$.data[*].code").value(contains("D3", "D4")))
                .andExpect(jsonPath("$.page.hasMore").value(false));

        mvc.perform(get(admin.path("/departments")).cookie(admin.session()).param("filter[parentId]", root.toString()))
                .andExpect(jsonPath("$.data[*].code").value(contains("D2", "D3")));
        mvc.perform(get(admin.path("/departments"))
                        .cookie(admin.session())
                        .param("filter[parentId][isNull]", "true")
                        .param("filter[isActive]", "true"))
                .andExpect(jsonPath("$.data[*].code").value(contains("D1")));
        mvc.perform(get(admin.path("/departments")).cookie(admin.session()).param("q", "d3"))
                .andExpect(jsonPath("$.data[*].code").value(contains("D3")));
        mvc.perform(get(admin.path("/departments")).cookie(admin.session()).param("sort", "-code"))
                .andExpect(jsonPath("$.data[0].code").value("D4"));
        mvc.perform(get(admin.path("/departments")).cookie(admin.session()).param("filter[budget]", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(admin.path("/departments/tree")).cookie(admin.session()))
                .andExpect(jsonPath("$.data[*].code").value(contains("D1")));
        mvc.perform(get(admin.path("/departments/tree")).cookie(admin.session()).param("includeInactive", "true"))
                .andExpect(jsonPath("$.data[*].code").value(contains("D1", "D4")));
    }

    @Test
    void readersCannotChangeDepartmentsAndConcurrentEditsConflict() throws Exception {
        Admin admin = fixtures.admin();
        UUID department = fixtures.department(admin, "R", null, null);
        TestUser reader = auth.user();
        auth.assign(reader, auth.customRole("org.department.read"), admin.company());
        Cookie readerSession = auth.login(reader);

        mvc.perform(get(admin.path("/departments/" + department)).cookie(readerSession))
                .andExpect(status().isOk());
        mvc.perform(unsafe(patch(admin.path("/departments/" + department)))
                        .cookie(readerSession)
                        .header("If-Match", etag(0))
                        .contentType(MERGE_PATCH)
                        .content(json("name", "nope")))
                .andExpect(status().isForbidden());

        patchDepartment(admin, department, 0, json("name", "First")).andExpect(status().isOk());
        patchDepartment(admin, department, 0, json("name", "Stale")).andExpect(status().isPreconditionFailed());
        mvc.perform(unsafe(patch(admin.path("/departments/" + department)))
                        .cookie(admin.session())
                        .contentType(MERGE_PATCH)
                        .content(json("name", "No If-Match")))
                .andExpect(status().isPreconditionRequired());
        patchDepartment(admin, department, 1, json("code", "CHANGED")).andExpect(status().isBadRequest());
    }

    private ResultActions create(Admin admin, String body) throws Exception {
        return mvc.perform(unsafe(post(admin.path("/departments")))
                .cookie(admin.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchDepartment(Admin admin, UUID department, int version, String body) {
        try {
            return mvc.perform(unsafe(patch(admin.path("/departments/" + department)))
                    .cookie(admin.session())
                    .header("If-Match", etag(version))
                    .contentType(MERGE_PATCH)
                    .content(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions setActive(Admin admin, UUID department, int version, boolean active) throws Exception {
        return mvc.perform(
                unsafe(post(admin.path("/departments/" + department + (active ? "/activate" : "/deactivate"))))
                        .cookie(admin.session())
                        .header("If-Match", etag(version)));
    }

    private static MockHttpServletResponse response(ResultActions actions) {
        return actions.andReturn().getResponse();
    }

    private void inCompany(UUID company, Runnable action) {
        CurrentContext.runWith(
                RequestContext.forRequest("test-" + UUID.randomUUID()).withCompany(company),
                () -> tx.executeWithoutResult(s -> action.run()));
    }

    private List<String> auditActions(UUID company, UUID entity) {
        return CurrentContext.callWith(
                RequestContext.forRequest("test-" + UUID.randomUUID()).withCompany(company),
                () -> tx.execute(s -> dsl.select(AUDIT_LOG.ACTION)
                        .from(AUDIT_LOG)
                        .where(AUDIT_LOG.ENTITY_ID.eq(entity))
                        .orderBy(AUDIT_LOG.OCCURRED_AT)
                        .fetch(AUDIT_LOG.ACTION)));
    }

    private Object parentOf(UUID company, UUID department) {
        return CurrentContext.callWith(
                RequestContext.forRequest("test-" + UUID.randomUUID()).withCompany(company),
                () -> tx.execute(
                        s -> dsl.fetchValue("SELECT parent_id FROM org.departments WHERE id = ?", department)));
    }

    /**
     * Waits until {@code expected} sessions are blocked on a lock (advisory or row), or until one of
     * the requests has already finished (e.g. as a deadlock victim).
     */
    static void awaitLockWaiters(int expected, CompletableFuture<?>... requests) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            while (Instant.now().isBefore(deadline)) {
                try (ResultSet rs = s.executeQuery("SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted")) {
                    rs.next();
                    if (rs.getInt(1) >= expected
                            || java.util.Arrays.stream(requests).anyMatch(CompletableFuture::isDone)) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("Requests did not wait for each other");
    }
}
