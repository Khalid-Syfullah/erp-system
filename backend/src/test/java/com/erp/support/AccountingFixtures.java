package com.erp.support;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.ProcurementFixtures.id;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.erp.support.AuthTestSupport.TestUser;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Accounting test setups through the API. Every company is set up for accounting when it is created
 * ({@code org.company.created}, ADR-038): the STANDARD_SME chart, mappings, journals and the current
 * fiscal year. {@link #enable} gives a user every accounting permission, adds an approver for
 * segregation of duties and a USD bank account on GL 1010 ("Bank – main"); {@link #balances} reads
 * the trial balance and {@link #lines} the GL lines booked for a source document.
 */
@TestComponent
public class AccountingFixtures {

    public static final String[] ALL_ACCOUNTING = {
        "accounting.account.read",
        "accounting.account.manage",
        "accounting.account_mapping.manage",
        "accounting.fiscal_year.manage",
        "accounting.fiscal_year.close",
        "accounting.period.read",
        "accounting.period.soft_close",
        "accounting.period.close",
        "accounting.period.reopen",
        "accounting.period.post_soft_closed",
        "accounting.journal.manage",
        "accounting.journal_entry.read",
        "accounting.journal_entry.create",
        "accounting.journal_entry.post",
        "accounting.journal_entry.reverse",
        "accounting.ar.read",
        "accounting.ap.read",
        "accounting.bank_account.read",
        "accounting.bank_account.manage",
        "accounting.payment.read",
        "accounting.payment.create",
        "accounting.payment.post",
        "accounting.payment.void",
        "accounting.payment.allocate",
        "accounting.payment.unallocate",
        "accounting.expense.read",
        "accounting.expense.create",
        "accounting.expense.post",
        "accounting.bank_reconciliation.manage",
        "accounting.report.read",
        "accounting.settings.manage",
        "org.tax_code.read",
        "org.tax_code.manage",
        "org.exchange_rate.read",
        "org.exchange_rate.manage"
    };

    /** A clerk without the privileged accounting permissions (no soft-closed posting, no period close). */
    public static final String[] CLERK = {
        "accounting.account.read",
        "accounting.period.read",
        "accounting.journal_entry.read",
        "accounting.journal_entry.create",
        "accounting.journal_entry.post",
        "accounting.ar.read",
        "accounting.ap.read",
        "accounting.payment.read",
        "accounting.payment.create",
        "accounting.payment.post",
        "accounting.payment.allocate",
        "accounting.bank_account.read",
        "accounting.report.read"
    };

    /** A company's books as a test sees them. */
    public record Books(
            UUID company,
            TestUser user,
            Cookie session,
            Cookie approver,
            TestUser approverUser,
            UUID bankAccount,
            Map<String, UUID> accounts) {

        public String path(String suffix) {
            return "/api/v1/companies/" + company + suffix;
        }

        public UUID account(String code) {
            UUID id = accounts.get(code);
            if (id == null) {
                throw new AssertionError("No account " + code);
            }
            return id;
        }

        public LocalDate today() {
            return LocalDate.now(ZoneId.of("America/New_York"));
        }
    }

    private final MockMvc mvc;
    private final AuthTestSupport auth;

    public AccountingFixtures(MockMvc mvc, AuthTestSupport auth) {
        this.mvc = mvc;
        this.auth = auth;
    }

    /** A new company whose user holds every accounting permission. */
    public Books setup() throws Exception {
        return books(auth.company());
    }

    /**
     * The books of an existing company (e.g. an inventory, procurement or sales setup's) kept by a new
     * user with every accounting permission.
     */
    public Books books(UUID company) throws Exception {
        TestUser user = auth.enrollMfa(auth.user());
        auth.assign(user, auth.customRole(ALL_ACCOUNTING), company);
        auth.invalidatePermissionCache();
        Cookie session = auth.login(user);
        return enable(company, user, session);
    }

    /** Gives the user every accounting permission in the company and prepares the books. */
    public Books enable(UUID company, TestUser user, Cookie session) throws Exception {
        auth.assign(user, auth.customRole(ALL_ACCOUNTING), company);
        // The accounting role holds sensitive permissions (period reopen, payment void): MFA (SECURITY.md §4.2).
        TestUser approverUser = auth.enrollMfa(auth.user());
        auth.assign(approverUser, auth.customRole(ALL_ACCOUNTING), company);
        auth.invalidatePermissionCache();
        Cookie approver = auth.login(approverUser);
        String base = "/api/v1/companies/" + company;
        Map<String, UUID> accounts = accounts(session, base);
        UUID bank = id(
                mvc.perform(unsafe(MockMvcRequestBuilders.post(base + "/bank-accounts"))
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "name",
                                "Main bank",
                                "accountId",
                                accounts.get("1010"),
                                "currencyCode",
                                "USD",
                                "bankName",
                                "First Bank",
                                "accountNumber",
                                "12345678"))),
                201);
        return new Books(company, user, session, approver, approverUser, bank, accounts);
    }

    /** A new postable account (not a system account) under the given type and subtype. */
    public UUID account(Books b, String code, String type, String subtype) throws Exception {
        UUID id = id(
                create(
                        b,
                        b.session(),
                        "/accounts",
                        OrgFixtures.map(
                                "code",
                                code,
                                "name",
                                "Account " + code,
                                "accountType",
                                type,
                                "accountSubtype",
                                subtype)),
                201);
        b.accounts().put(code, id);
        return id;
    }

    /** A user of the company with exactly the given permissions. */
    public Cookie user(Books b, String... permissions) throws Exception {
        TestUser user = auth.enrollMfa(auth.user());
        auth.assign(user, auth.customRole(permissions), b.company());
        auth.invalidatePermissionCache();
        return auth.login(user);
    }

    public Map<String, UUID> accounts(Cookie session, String base) throws Exception {
        String body = mvc.perform(get(base + "/accounts").cookie(session).param("limit", "200"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> codes = JsonPath.read(body, "$.data[*].code");
        List<String> ids = JsonPath.read(body, "$.data[*].id");
        Map<String, UUID> result = new HashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            result.put(codes.get(i), UUID.fromString(ids.get(i)));
        }
        return result;
    }

    /** Closing balance (debit − credit) per account code over all posted lines. */
    public Map<String, BigDecimal> balances(Books b) throws Exception {
        String body = body(
                b,
                "/reports/trial-balance?from=" + b.today().minusYears(5) + "&to="
                        + b.today().plusYears(4));
        List<String> codes = JsonPath.read(body, "$.rows[*].account.code");
        List<String> closing = JsonPath.read(body, "$.rows[*].closing");
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            result.put(codes.get(i), new BigDecimal(closing.get(i)));
        }
        return result;
    }

    public BigDecimal balance(Books b, String code) throws Exception {
        return balances(b).getOrDefault(code, BigDecimal.ZERO);
    }

    /**
     * The GL lines booked for a source document as {@code code → signed amount} (debit +, credit −),
     * summed over its entries.
     */
    public Map<String, BigDecimal> lines(Books b, String module, String type, UUID sourceId) throws Exception {
        String list = body(
                b,
                "/journal-entries?filter[sourceModule]=" + module + "&filter[sourceType]=" + type + "&filter[sourceId]="
                        + sourceId);
        List<String> ids = JsonPath.read(list, "$.data[*].id");
        Map<UUID, String> codes = new HashMap<>();
        b.accounts().forEach((code, id) -> codes.put(id, code));
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String id : ids) {
            String entry = body(b, "/journal-entries/" + id);
            List<String> accounts = JsonPath.read(entry, "$.lines[*].accountId");
            List<String> debits = JsonPath.read(entry, "$.lines[*].debit");
            List<String> credits = JsonPath.read(entry, "$.lines[*].credit");
            for (int i = 0; i < accounts.size(); i++) {
                String code = codes.getOrDefault(UUID.fromString(accounts.get(i)), accounts.get(i));
                result.merge(
                        code, new BigDecimal(debits.get(i)).subtract(new BigDecimal(credits.get(i))), BigDecimal::add);
            }
        }
        result.replaceAll((k, v) -> v.stripTrailingZeros());
        result.values().removeIf(v -> v.signum() == 0);
        return result;
    }

    /** Expected {@link #lines} or balances: {@code code, amount, code, amount, …}. */
    public static Map<String, BigDecimal> amounts(String... pairs) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], new BigDecimal(pairs[i + 1]).stripTrailingZeros());
        }
        return result;
    }

    /** The non-zero balances, comparable with {@link #amounts}. */
    public static Map<String, BigDecimal> nonZero(Map<String, BigDecimal> balances) {
        Map<String, BigDecimal> result = new HashMap<>();
        balances.forEach((code, amount) -> {
            if (amount.signum() != 0) {
                result.put(code, amount.stripTrailingZeros());
            }
        });
        return result;
    }

    /** A balanced manual entry body: {@code lines} as account code → signed amount. */
    public Map<String, Object> entry(Books b, LocalDate date, Map<String, String> lines) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        lines.forEach((code, amount) -> {
            BigDecimal value = new BigDecimal(amount);
            rows.add(OrgFixtures.map(
                    "accountId",
                    b.account(code),
                    value.signum() >= 0 ? "debit" : "credit",
                    value.abs().toPlainString()));
        });
        return OrgFixtures.map("entryDate", date, "description", "Test entry", "lines", rows);
    }

    public ResultActions create(Books b, Cookie session, String path, Object body) throws Exception {
        return mvc.perform(unsafe(MockMvcRequestBuilders.post(b.path(path)))
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(body)));
    }

    /** POST {path} with If-Match, an optional Idempotency-Key and an optional JSON body. */
    public ResultActions action(Books b, Cookie session, String path, int version, String key, Object body)
            throws Exception {
        MockHttpServletRequestBuilder request = unsafe(MockMvcRequestBuilders.post(b.path(path)))
                .cookie(session)
                .header("If-Match", OrgFixtures.etag(version));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String s ? s : OrgFixtures.JSON_MAPPER.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    public String body(Books b, String path) throws Exception {
        MvcResult result = mvc.perform(get(b.path(path)).cookie(b.session())).andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError(path + ": " + result.getResponse().getStatus() + " "
                    + result.getResponse().getContentAsString());
        }
        return result.getResponse().getContentAsString();
    }

    public <T> T read(Books b, String path, String jsonPath) throws Exception {
        return JsonPath.read(body(b, path), jsonPath);
    }

    public int version(Books b, String path) throws Exception {
        return JsonPath.read(body(b, path), "$.version");
    }

    /** A period as tests see it. */
    public record Period(UUID id, int no, LocalDate start, LocalDate end, String status, int version) {}

    /** The periods of the fiscal year containing {@code date}, in order. */
    public List<Period> periods(Books b, LocalDate date) throws Exception {
        String years = body(b, "/fiscal-years?limit=50");
        List<String> ids = JsonPath.read(years, "$.data[*].id");
        List<String> starts = JsonPath.read(years, "$.data[*].startDate");
        List<String> ends = JsonPath.read(years, "$.data[*].endDate");
        for (int i = 0; i < ids.size(); i++) {
            if (!date.isBefore(LocalDate.parse(starts.get(i))) && !date.isAfter(LocalDate.parse(ends.get(i)))) {
                String year = body(b, "/fiscal-years/" + ids.get(i));
                List<Map<String, Object>> rows = JsonPath.read(year, "$.periods");
                return rows.stream()
                        .map(r -> new Period(
                                UUID.fromString((String) r.get("id")),
                                (Integer) r.get("periodNo"),
                                LocalDate.parse((String) r.get("startDate")),
                                LocalDate.parse((String) r.get("endDate")),
                                (String) r.get("status"),
                                (Integer) r.get("version")))
                        .toList();
            }
        }
        throw new AssertionError("No fiscal year covers " + date);
    }

    /** The fiscal year containing {@code date} as {@code id, version}. */
    public Map<String, Object> year(Books b, LocalDate date) throws Exception {
        String years = body(b, "/fiscal-years?limit=50");
        List<Map<String, Object>> rows = JsonPath.read(years, "$.data");
        return rows.stream()
                .filter(r -> !date.isBefore(LocalDate.parse((String) r.get("startDate")))
                        && !date.isAfter(LocalDate.parse((String) r.get("endDate"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No fiscal year covers " + date));
    }

    /** POST /periods/{id}/{action} at the period's current version with a fresh Idempotency-Key. */
    public ResultActions period(Books b, Cookie session, Period period, String action, Object body) throws Exception {
        int version = version(b, "/periods/" + period.id());
        return action(
                b, session, "/periods/" + period.id() + "/" + action, version, "period-" + UUID.randomUUID(), body);
    }

    /** Creates and posts a manual entry; returns its ID. */
    public UUID posted(Books b, LocalDate date, Map<String, String> lines) throws Exception {
        UUID entry = id(create(b, b.session(), "/journal-entries", entry(b, date, lines)), 201);
        MvcResult result = action(b, b.session(), "/journal-entries/" + entry + "/post", 0, "post-" + entry, null)
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("Post failed: " + result.getResponse().getContentAsString());
        }
        return entry;
    }

    /** The open item of a source document: {@code kind} is "receivables" or "payables". */
    public Map<String, Object> openItem(Books b, String kind, UUID sourceId) throws Exception {
        List<Map<String, Object>> items =
                JsonPath.read(body(b, "/" + kind + "?filter[sourceId]=" + sourceId), "$.data");
        if (items.size() != 1) {
            throw new AssertionError("Expected one open item for " + sourceId + ", found " + items.size());
        }
        return items.getFirst();
    }

    /** A payment body; {@code allocations} as open item ID → amount. */
    public Map<String, Object> payment(
            Books b, String direction, UUID partner, UUID bankAccount, String amount, Map<UUID, String> allocations) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        allocations.forEach((item, value) -> rows.add(OrgFixtures.map("openItemId", item, "amount", value)));
        return OrgFixtures.map(
                "direction",
                direction,
                "partnerId",
                partner,
                "bankAccountId",
                bankAccount,
                "amount",
                amount,
                "method",
                "BANK_TRANSFER",
                "allocations",
                rows);
    }

    /** Creates and posts a payment; returns its ID. */
    public UUID postedPayment(Books b, Map<String, Object> body) throws Exception {
        UUID payment = id(create(b, b.session(), "/payments", body), 201);
        MvcResult result = action(b, b.session(), "/payments/" + payment + "/post", 0, "pay-" + payment, null)
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("Post failed: " + result.getResponse().getContentAsString());
        }
        return payment;
    }

    /** The current period of the books (containing today). */
    public UUID currentPeriod(Books b) throws Exception {
        String body = body(b, "/periods?filter[startDate][lte]=" + b.today() + "&limit=200");
        List<String> ids = JsonPath.read(body, "$.data[*].id");
        List<String> ends = JsonPath.read(body, "$.data[*].endDate");
        for (int i = 0; i < ids.size(); i++) {
            if (!LocalDate.parse(ends.get(i)).isBefore(b.today())) {
                return UUID.fromString(ids.get(i));
            }
        }
        throw new AssertionError("No current period");
    }
}
