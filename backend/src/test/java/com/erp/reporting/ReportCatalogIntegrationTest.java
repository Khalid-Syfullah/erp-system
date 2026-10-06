package com.erp.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.reporting.application.ReportCatalog;
import com.erp.reporting.application.ReportScope;
import com.erp.reporting.domain.ParameterType;
import com.erp.reporting.domain.ReportColumn;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportParameter;
import com.erp.reporting.domain.ReportParameters;
import com.erp.reporting.domain.ReportSourceKind;
import com.erp.reporting.persistence.ViewReports;
import com.erp.support.AuthTestSupport;
import com.erp.support.IntegrationTest;
import com.erp.support.ReportingFixtures;
import com.erp.support.TestDatabase;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The report catalogue and its database contract (DATABASE.md §11, ADR-040): the code catalogue
 * equals the seeded definitions and uses documented permissions; every view-backed report's query
 * produces its columns and runs for every grouping; the {@code v_rpt_*} views are security-invoker
 * views readable only by {@code erp_reporting}, which writes nothing and sees one company at a time.
 */
class ReportCatalogIntegrationTest extends IntegrationTest {

    static final String[] ALL_REPORTS = {
        "reporting.sales.read",
        "reporting.procurement.read",
        "reporting.inventory.read",
        "reporting.hr.read",
        "reporting.export.create",
        "reporting.saved_report.share",
        "inventory.valuation.read",
        "accounting.report.read",
        "accounting.ar.read",
        "accounting.ap.read",
        "payroll.report.read"
    };

    private static final Path SECURITY_MD = Path.of("..", "docs", "SECURITY.md");
    private static final Pattern CODE = Pattern.compile("`([a-z_]+\\.[a-z_]+\\.[a-z_]+)`");

    @Autowired
    ReportCatalog catalog;

    @Autowired
    ViewReports views;

    @Autowired
    DSLContext dsl;

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    ReportingFixtures rep;

    @Test
    void theCatalogueEqualsTheSeededDefinitionsAndUsesDocumentedPermissions() throws Exception {
        Map<String, String> seeded = new HashMap<>();
        dsl.fetch(
                        "SELECT code, name, owner_module, array_to_string(permission_codes, ',') FROM reporting.report_definitions")
                .forEach(r -> seeded.put(
                        r.get(0, String.class),
                        r.get(1, String.class) + "|" + r.get(2, String.class) + "|" + r.get(3, String.class)));
        Map<String, String> code = new HashMap<>();
        catalog.all()
                .forEach(
                        d -> code.put(d.code(), d.name() + "|" + d.module() + "|" + String.join(",", d.permissions())));
        assertThat(code).hasSize(36).isEqualTo(seeded);

        String document = Files.readString(SECURITY_MD);
        String table = document.substring(document.indexOf("### 4.2"), document.indexOf("Permissions flagged"));
        Set<String> documented = new LinkedHashSet<>();
        Matcher matcher = CODE.matcher(table);
        while (matcher.find()) {
            documented.add(matcher.group(1));
        }
        assertThat(documented)
                .containsAll(catalog.all().stream()
                        .flatMap(d -> d.permissions().stream())
                        .collect(Collectors.toSet()))
                .contains(ALL_REPORTS);
    }

    @Test
    void everyViewReportHasAQueryProducingItsColumns() {
        Set<String> viewReports = catalog.all().stream()
                .filter(d -> d.source() == ReportSourceKind.VIEWS)
                .map(ReportDefinition::code)
                .collect(Collectors.toSet());
        assertThat(views.codes()).isEqualTo(viewReports);
        ReportScope scope = new ReportScope(UUID.randomUUID(), null, LocalDate.now());
        for (ReportDefinition definition : catalog.all()) {
            if (definition.source() != ReportSourceKind.VIEWS) {
                assertThat(definition.module()).isEqualTo("accounting");
                continue;
            }
            for (ReportParameters parameters : variants(definition)) {
                assertThat(views.columnNames(definition, parameters, scope))
                        .as(definition.code() + " " + parameters)
                        .containsAll(definition.columns().stream()
                                .map(ReportColumn::key)
                                .toList());
            }
        }
    }

    /** Every view report and grouping runs (the SQL is valid) for a new company, also with every filter set. */
    @Test
    void everyViewReportRunsForEveryGroupingAndFilter() throws Exception {
        UUID company = auth.company();
        Cookie session = rep.user(company, ALL_REPORTS);
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        for (ReportDefinition definition : catalog.all()) {
            if (definition.source() != ReportSourceKind.VIEWS) {
                continue;
            }
            for (String query : queries(definition, today)) {
                String body = rep.run(session, company, definition.code(), query);
                assertThat((String) JsonPath.read(body, "$.report.code")).isEqualTo(definition.code());
                List<Object> columns = JsonPath.read(body, "$.columns[*].key");
                assertThat(columns).hasSize(definition.columns().size());
            }
        }
        // The catalogue lists every report for this user, with paths and parameters.
        String catalogue = mvc.perform(
                        get(ReportingFixtures.path(company, "/reports")).cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(catalogue, "$[*].code")).hasSize(36);
    }

    @Test
    void reportingViewsAreSecurityInvokerAndOnlyTheReportingRoleReadsThem() throws SQLException {
        List<String> checked = new ArrayList<>();
        try (Connection c = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                    SELECT n.nspname || '.' || c.relname,
                           coalesce(c.reloptions, '{}') @> ARRAY['security_invoker=true'],
                           has_table_privilege('erp_reporting', c.oid, 'SELECT'),
                           has_table_privilege('erp_app', c.oid, 'SELECT'),
                           has_table_privilege('erp_app', c.oid, 'INSERT,UPDATE,DELETE')
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind = 'v' AND c.relname LIKE 'v\\_rpt\\_%' AND n.nspowner = 'erp_owner'::regrole
                    """);
            while (rs.next()) {
                String view = rs.getString(1);
                checked.add(view);
                assertThat(rs.getBoolean(2)).as("security_invoker on " + view).isTrue();
                assertThat(rs.getBoolean(3)).as("erp_reporting reads " + view).isTrue();
                assertThat(rs.getBoolean(4)).as("erp_app reads " + view).isFalse();
                assertThat(rs.getBoolean(5)).as("erp_app writes " + view).isFalse();
            }
            // The reporting role writes nothing anywhere.
            ResultSet writable = s.executeQuery("""
                    SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind IN ('r', 'p', 'v') AND n.nspowner = 'erp_owner'::regrole
                      AND has_table_privilege('erp_reporting', c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE')
                    """);
            writable.next();
            assertThat(writable.getLong(1)).isZero();
        }
        assertThat(checked)
                .contains(
                        "inventory.v_rpt_stock_on_hand",
                        "inventory.v_rpt_stock_valuation",
                        "inventory.v_rpt_stock_movements",
                        "procurement.v_rpt_purchase_lines",
                        "procurement.v_rpt_supplier_bills",
                        "sales.v_rpt_sales_lines",
                        "sales.v_rpt_order_backlog",
                        "accounting.v_rpt_gl_lines",
                        "accounting.v_rpt_open_items",
                        "hr.v_rpt_headcount",
                        "payroll.v_rpt_payroll_summary")
                .hasSize(26);
    }

    @Test
    void theReportingRoleSeesOneCompanyAndNoPersonalData() throws Exception {
        UUID a = auth.company();
        UUID b = auth.company();
        auth.branch(a, "A1");
        auth.branch(b, "B1");
        try (Connection c = TestDatabase.connectAs("erp_reporting", TestDatabase.REPORTING_PASSWORD)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                assertThat(count(s, "SELECT count(*) FROM org.v_rpt_branches")).isZero();
                s.execute("SELECT set_config('app.company_id', '" + a + "', true)");
                ResultSet rs = s.executeQuery("SELECT DISTINCT company_id FROM org.v_rpt_branches");
                List<String> companies = new ArrayList<>();
                while (rs.next()) {
                    companies.add(rs.getString(1));
                }
                assertThat(companies).containsExactly(a.toString());
                // Tables are not readable as such, encrypted personal data not at all.
                assertThatThrownBy(() -> s.executeQuery("SELECT national_id_encrypted FROM hr.employees"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("42501");
            }
            c.rollback();
            try (Statement s = c.createStatement()) {
                assertThatThrownBy(() -> s.executeUpdate(
                                "INSERT INTO org.branches (company_id, code, name) VALUES ('" + a + "', 'X', 'X')"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("42501");
            }
            c.rollback();
        }
    }

    private static long count(Statement s, String sql) throws SQLException {
        ResultSet rs = s.executeQuery(sql);
        rs.next();
        return rs.getLong(1);
    }

    /** Parameter sets covering every value of every choice parameter (defaults otherwise). */
    private static List<ReportParameters> variants(ReportDefinition definition) {
        List<ReportParameters> variants = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Map<String, String> values : valueSets(definition, today)) {
            Map<String, Object> typed = new HashMap<>();
            for (ReportParameter p : definition.parameters()) {
                String raw = values.get(p.name());
                if (raw == null) {
                    continue;
                }
                typed.put(
                        p.name(),
                        switch (p.type()) {
                            case DATE -> LocalDate.parse(raw);
                            case ID -> UUID.fromString(raw);
                            case INTEGER -> Integer.parseInt(raw);
                            case BOOLEAN -> Boolean.parseBoolean(raw);
                            case ENUM -> raw;
                        });
            }
            variants.add(new ReportParameters(typed));
        }
        return variants;
    }

    private static List<String> queries(ReportDefinition definition, LocalDate today) {
        return valueSets(definition, today).stream()
                .map(values -> values.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("&")))
                .toList();
    }

    /**
     * Required dates, defaults, each value of each choice in turn; and one set with every optional
     * filter (IDs, flags, the as-of date of historic reports) given.
     */
    private static List<Map<String, String>> valueSets(ReportDefinition definition, LocalDate today) {
        Map<String, String> base = new HashMap<>();
        for (ReportParameter p : definition.parameters()) {
            if (p.type() == ParameterType.DATE && p.required()) {
                base.put(p.name(), (p.name().equals("from") ? today.minusDays(40) : today).toString());
            } else if (p.defaultValue() != null) {
                base.put(p.name(), p.defaultValue().equals("today") ? today.toString() : p.defaultValue());
            }
        }
        List<Map<String, String>> sets = new ArrayList<>();
        sets.add(base);
        for (ReportParameter p : definition.parameters()) {
            if (p.type() == ParameterType.ENUM) {
                for (String value : p.values()) {
                    Map<String, String> set = new HashMap<>(base);
                    set.put(p.name(), value);
                    sets.add(set);
                    // The same with every optional date (e.g. historic as-of figures).
                    Map<String, String> dated = new HashMap<>(set);
                    for (ReportParameter d : definition.parameters()) {
                        if (d.type() == ParameterType.DATE
                                && !d.required()
                                && !d.name().startsWith("compare")) {
                            dated.putIfAbsent(d.name(), today.minusDays(1).toString());
                        }
                    }
                    dated.computeIfPresent("from", (k, v) -> today.minusDays(40).toString());
                    sets.add(dated);
                }
            }
        }
        Map<String, String> filtered = new HashMap<>(base);
        for (ReportParameter p : definition.parameters()) {
            switch (p.type()) {
                case ID -> filtered.put(p.name(), UUID.randomUUID().toString());
                case BOOLEAN -> filtered.put(p.name(), "true");
                case DATE -> filtered.putIfAbsent(p.name(), today.minusDays(1).toString());
                default -> {}
            }
        }
        if (filtered.containsKey("compareFrom")) {
            filtered.put("compareTo", today.minusDays(1).toString());
            filtered.put("compareFrom", today.minusDays(30).toString());
        }
        sets.add(filtered);
        return sets;
    }
}
