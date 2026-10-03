package com.erp.partners;

import static com.erp.db.admin.Tables.AUDIT_LOG;
import static com.erp.db.auth.Tables.SESSIONS;
import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.OrgFixtures.etag;
import static com.erp.support.OrgFixtures.json;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.ProcurementFixtures;
import com.erp.support.ProcurementFixtures.P2P;
import com.erp.support.TestDatabase;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Partners and suppliers: master data, status, profiles, encrypted bank accounts (API.md §17.4). */
class PartnersIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProcurementFixtures proc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    com.erp.partners.api.PartnersFacade facade;

    private P2P p;

    @BeforeEach
    void setUp() throws Exception {
        p = proc.setup();
    }

    @Test
    void partnersAreCreatedEditedListedAndSearched() throws Exception {
        UUID partner = id(
                create(
                        "/partners",
                        json(
                                "code",
                                "GLOBEX",
                                "name",
                                "Globex Corporation",
                                "partnerType",
                                "ORGANIZATION",
                                "taxRegistrationNo",
                                "DE123",
                                "email",
                                "buy@globex.example")),
                201);
        create("/partners", json("code", "GLOBEX", "name", "Again", "partnerType", "ORGANIZATION"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        create("/partners", json("code", "bad code", "name", "X", "partnerType", "ORGANIZATION"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(patch(p.path("/partners/" + partner)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(json("legalName", "Globex Corp. Ltd", "email", "not-an-email")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(patch(p.path("/partners/" + partner)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(json("legalName", "Globex Corp. Ltd", "phone", OrgFixtures.NULL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Globex Corp. Ltd"))
                .andExpect(jsonPath("$.code").value("GLOBEX"));
        mvc.perform(get(p.path("/partners")).cookie(p.session()).param("q", "globex"))
                .andExpect(jsonPath("$.data[*].code").value(contains("GLOBEX")));
        mvc.perform(get(p.path("/partners")).cookie(p.session()).param("filter[taxRegistrationNo]", "DE123"))
                .andExpect(jsonPath("$.data[*].code").value(contains("GLOBEX")));
        mvc.perform(get(p.path("/suppliers")).cookie(p.session()))
                .andExpect(jsonPath("$.data[*].code").value(contains("ACME")))
                .andExpect(jsonPath("$.data[0].profile.currencyCode").value("USD"));
    }

    @Test
    void addressesAndContactsKeepOneDefaultEach() throws Exception {
        String base = "/partners/" + p.supplier();
        UUID first = id(
                create(
                        base + "/addresses",
                        json("addressType", "BILLING", "line1", "1 Main St", "countryCode", "US", "isDefault", true)),
                201);
        UUID second = id(
                create(
                        base + "/addresses",
                        json("addressType", "BILLING", "line1", "2 High St", "countryCode", "GB", "isDefault", true)),
                201);
        create(base + "/addresses", json("addressType", "BILLING", "line1", "X", "countryCode", "ZZ"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(p.path(base + "/addresses")).cookie(p.session()))
                .andExpect(jsonPath("$.data[?(@.isDefault == true)].id").value(contains(second.toString())));
        mvc.perform(unsafe(delete(p.path(base + "/addresses/" + first))).cookie(p.session()))
                .andExpect(status().isNoContent());
        id(
                create(base + "/contacts", json("name", "Jane Buyer", "email", "jane@acme.example", "isPrimary", true)),
                201);
        mvc.perform(get(p.path(base)).cookie(p.session()))
                .andExpect(jsonPath("$.addresses.length()").value(1))
                .andExpect(jsonPath("$.contacts[0].isPrimary").value(true))
                .andExpect(jsonPath("$.supplierProfile.paymentTermsId")
                        .value(p.terms().toString()));
    }

    @Test
    void statusFollowsItsStateMachine() throws Exception {
        String base = "/partners/" + p.supplier();
        mvc.perform(unsafe(post(p.path(base + "/activate"))).cookie(p.session()).header("If-Match", etag(0)))
                .andExpect(status().isConflict());
        mvc.perform(unsafe(post(p.path(base + "/deactivate")))
                        .cookie(p.session())
                        .header("If-Match", etag(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(unsafe(post(p.path(base + "/block"))).cookie(p.session()).header("If-Match", etag(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
        mvc.perform(unsafe(post(p.path(base + "/activate"))).cookie(p.session()).header("If-Match", etag(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void supplierProfilesValidateTheirReferences() throws Exception {
        UUID partner =
                id(create("/partners", json("code", "INITECH", "name", "Initech", "partnerType", "ORGANIZATION")), 201);
        UUID customerGroup =
                id(create("/partner-groups", json("code", "VIP", "name", "VIP", "appliesTo", "CUSTOMER")), 201);
        UUID supplierGroup =
                id(create("/partner-groups", json("code", "LOCAL", "name", "Local", "appliesTo", "SUPPLIER")), 201);
        profile(partner, 0, json("currencyCode", "XXX")).andExpect(status().isUnprocessableContent());
        profile(partner, 0, json("currencyCode", "USD", "supplierGroupId", customerGroup))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].pointer").value("/supplierGroupId"));
        profile(partner, 0, json("currencyCode", "USD", "supplierGroupId", supplierGroup, "leadTimeDays", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // Replacing needs the profile's current version.
        profile(partner, 0, json("currencyCode", "EUR")).andExpect(status().isPreconditionFailed());
        profile(partner, 1, json("currencyCode", "EUR", "supplierGroupId", supplierGroup))
                .andExpect(status().isOk());
        mvc.perform(get(p.path("/suppliers"))
                        .cookie(p.session())
                        .param("filter[supplierGroupId]", supplierGroup.toString()))
                .andExpect(jsonPath("$.data[*].code").value(contains("INITECH")));
    }

    @Test
    void bankAccountsAreEncryptedMaskedAndRevealedWithStepUp() throws Exception {
        TestUser finance = auth.enrollMfa(auth.user());
        auth.assign(
                finance,
                auth.customRole("partners.partner.read", "partners.partner.read_bank", "partners.partner.manage_bank"),
                p.inv().company());
        Cookie session = auth.login(finance);
        String base = "/partners/" + p.supplier() + "/bank-accounts";

        mvc.perform(unsafe(post(p.path(base)))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "bankName",
                                "Bank",
                                "accountHolder",
                                "Acme",
                                "accountNumber",
                                "12345678",
                                "iban",
                                "DE89 3704 0044 0532 0130 01")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_IBAN"));
        mvc.perform(unsafe(post(p.path(base)))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "bankName",
                                "Bank",
                                "accountHolder",
                                "Acme",
                                "accountNumber",
                                "12",
                                "currencyCode",
                                "ZZZ")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(2));
        String created = mvc.perform(unsafe(post(p.path(base)))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "bankName",
                                "Bank",
                                "accountHolder",
                                "Acme",
                                "accountNumber",
                                "0012 3456 7890",
                                "iban",
                                "DE89 3704 0044 0532 0130 00",
                                "isDefault",
                                true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumberMasked").value("****7890"))
                .andExpect(jsonPath("$.hasIban").value(true))
                .andExpect(jsonPath("$.accountNumber").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID account = UUID.fromString(JsonPath.read(created, "$.id"));

        // Stored encrypted: no clear number anywhere in the row.
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT encode(account_number_encrypted, 'escape') FROM "
                        + "partners.partner_bank_accounts WHERE id = '" + account + "'")) {
            rs.next();
            assertThat(rs.getString(1)).doesNotContain("001234567890");
        }
        mvc.perform(get(p.path(base)).cookie(session))
                .andExpect(jsonPath("$.data[0].last4").value("7890"))
                .andExpect(jsonPath("$.data[0].isDefault").value(true));
        // The buyer may not read bank details.
        mvc.perform(get(p.path(base)).cookie(p.session())).andExpect(status().isForbidden());

        mvc.perform(unsafe(post(p.path(base + "/" + account + "/reveal"))).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("001234567890"))
                .andExpect(jsonPath("$.iban").value("DE89370400440532013000"));
        long reveals = CurrentContext.callWith(
                RequestContext.forRequest("audit-check").withCompany(p.inv().company()),
                () -> tx.execute(s -> dsl.fetchCount(
                        AUDIT_LOG, AUDIT_LOG.ACTION.eq("VIEW_SENSITIVE").and(AUDIT_LOG.ENTITY_ID.eq(account)))));
        assertThat(reveals).isEqualTo(1);

        // Without a recent password confirmation, revealing needs step-up.
        dsl.update(SESSIONS)
                .set(SESSIONS.AUTHENTICATED_AT, OffsetDateTime.now().minusMinutes(10))
                .where(SESSIONS.USER_ID.eq(finance.id()))
                .execute();
        mvc.perform(unsafe(post(p.path(base + "/" + account + "/reveal"))).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
        mvc.perform(unsafe(delete(p.path(base + "/" + account))).cookie(session))
                .andExpect(status().isNoContent());
    }

    @Test
    void addressesAndContactsAreEditedAndRemoved() throws Exception {
        String base = "/partners/" + p.supplier();
        UUID address = id(
                create(base + "/addresses", json("addressType", "SHIPPING", "line1", "Dock 4", "countryCode", "US")),
                201);
        mvc.perform(unsafe(patch(p.path(base + "/addresses/" + address)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(json("countryCode", "ZZ")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(patch(p.path(base + "/addresses/" + address)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(json("line1", "Dock 5", "city", "Springfield", "isDefault", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line1").value("Dock 5"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(unsafe(patch(p.path(base + "/addresses/" + address)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(json("line1", "Stale")))
                .andExpect(status().isPreconditionFailed());

        UUID first = id(create(base + "/contacts", json("name", "First", "isPrimary", true)), 201);
        UUID second = id(create(base + "/contacts", json("name", "Second")), 201);
        mvc.perform(unsafe(patch(p.path(base + "/contacts/" + second)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(json("isPrimary", true, "roleTitle", "Sales", "email", "bad")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(unsafe(patch(p.path(base + "/contacts/" + second)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(json("isPrimary", true, "roleTitle", "Sales")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPrimary").value(true));
        mvc.perform(get(p.path(base + "/contacts")).cookie(p.session()))
                .andExpect(jsonPath("$.data[?(@.isPrimary == true)].id").value(contains(second.toString())));
        mvc.perform(unsafe(delete(p.path(base + "/contacts/" + first))).cookie(p.session()))
                .andExpect(status().isNoContent());
        mvc.perform(unsafe(delete(p.path(base + "/contacts/" + first))).cookie(p.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(p.path(base + "/addresses")).cookie(p.session()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void groupsAreListedEditedAndDeactivated() throws Exception {
        UUID group = id(create("/partner-groups", json("code", "EU", "name", "Europe", "appliesTo", "SUPPLIER")), 201);
        create("/partner-groups", json("code", "EU", "name", "Again", "appliesTo", "SUPPLIER"))
                .andExpect(status().isConflict());
        // The same code is fine for the other kind of group.
        create("/partner-groups", json("code", "EU", "name", "European customers", "appliesTo", "CUSTOMER"))
                .andExpect(status().isCreated());
        mvc.perform(get(p.path("/partner-groups/" + group)).cookie(p.session()))
                .andExpect(jsonPath("$.appliesTo").value("SUPPLIER"));
        mvc.perform(unsafe(patch(p.path("/partner-groups/" + group)))
                        .cookie(p.session())
                        .header("If-Match", etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(json("name", "European suppliers", "isActive", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
        mvc.perform(get(p.path("/partner-groups"))
                        .cookie(p.session())
                        .param("filter[appliesTo]", "SUPPLIER")
                        .param("filter[isActive]", "false"))
                .andExpect(jsonPath("$.data[*].name").value(contains("European suppliers")));
        // An inactive group is not given to profiles.
        mvc.perform(unsafe(put(p.path("/partners/" + p.supplier() + "/supplier-profile")))
                        .cookie(p.session())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currencyCode", "USD", "supplierGroupId", group)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void theFacadeServesOtherModules() throws Exception {
        var summaries = proc.inCompany(p, () -> facade.partners(java.util.List.of(p.supplier(), UUID.randomUUID())));
        assertThat(summaries).containsOnlyKeys(p.supplier());
        assertThat(summaries.get(p.supplier()).code()).isEqualTo("ACME");
        assertThat(proc.inCompany(p, () -> facade.partners(java.util.List.of())))
                .isEmpty();
        var supplier = proc.inCompany(p, () -> facade.supplier(p.supplier())).orElseThrow();
        assertThat(supplier.usable()).isTrue();
        assertThat(supplier.paymentTermsId()).isEqualTo(p.terms());
    }

    @Test
    void anotherCompanysPartnersAreUnreachable() throws Exception {
        P2P other = proc.setup();
        mvc.perform(get(other.path("/partners/" + p.supplier())).cookie(other.session()))
                .andExpect(status().isNotFound());
        mvc.perform(unsafe(put(other.path("/partners/" + p.supplier() + "/supplier-profile")))
                        .cookie(other.session())
                        .header("If-Match", etag(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currencyCode", "USD")))
                .andExpect(status().isNotFound());
        // Their profile cannot name our payment terms.
        UUID partner = id(
                mvc.perform(unsafe(post(other.path("/partners")))
                        .cookie(other.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", "X1", "name", "X", "partnerType", "ORGANIZATION"))),
                201);
        mvc.perform(unsafe(put(other.path("/partners/" + partner + "/supplier-profile")))
                        .cookie(other.session())
                        .header("If-Match", etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currencyCode", "USD", "paymentTermsId", p.terms())))
                .andExpect(status().isUnprocessableContent());
    }

    private ResultActions create(String path, String body) throws Exception {
        return mvc.perform(unsafe(post(p.path(path)))
                .cookie(p.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions profile(UUID partner, int version, String body) throws Exception {
        return mvc.perform(unsafe(put(p.path("/partners/" + partner + "/supplier-profile")))
                .cookie(p.session())
                .header("If-Match", etag(version))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
