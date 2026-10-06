package com.erp.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportingDomainTest {

    @Test
    void spreadsheetTextNeutralisesFormulasAndQuotesCsvFields() {
        assertThat(SpreadsheetText.neutralise("=1+2")).isEqualTo("'=1+2");
        assertThat(SpreadsheetText.neutralise("+1")).isEqualTo("'+1");
        assertThat(SpreadsheetText.neutralise("-1")).isEqualTo("'-1");
        assertThat(SpreadsheetText.neutralise("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(SpreadsheetText.neutralise("\tx")).isEqualTo("'\tx");
        assertThat(SpreadsheetText.neutralise("\rx")).isEqualTo("'\rx");
        assertThat(SpreadsheetText.neutralise("Acme")).isEqualTo("Acme");
        assertThat(SpreadsheetText.neutralise(null)).isEmpty();
        assertThat(SpreadsheetText.csv("a,b")).isEqualTo("\"a,b\"");
        assertThat(SpreadsheetText.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(SpreadsheetText.csv("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(SpreadsheetText.csv("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(SpreadsheetText.csv("plain")).isEqualTo("plain");
    }

    @Test
    void parametersAreTypedAndCanonical() {
        UUID id = UUID.randomUUID();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("to", LocalDate.of(2026, 1, 31));
        values.put("from", LocalDate.of(2026, 1, 1));
        values.put("customerId", id);
        values.put("groupBy", "MONTH");
        values.put("days", 30);
        values.put("includeZero", true);
        ReportParameters p = new ReportParameters(values);
        assertThat(p.requireDate("from")).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(p.id("customerId")).isEqualTo(id);
        assertThat(p.choice("groupBy")).isEqualTo("MONTH");
        assertThat(p.integer("days")).isEqualTo(30);
        assertThat(p.flag("includeZero")).isTrue();
        assertThat(p.flag("missing")).isFalse();
        assertThat(p.has("days")).isTrue();
        assertThat(p.text("missing")).isNull();
        assertThat(p.canonical()).startsWith("customerId=" + id + "&days=30&from=2026-01-01");
        assertThat(p.asStrings()).containsEntry("to", "2026-01-31");
        assertThat(p).isEqualTo(new ReportParameters(values)).hasSameHashCodeAs(new ReportParameters(values));
        assertThat(p.toString()).isEqualTo(p.canonical());
        assertThat(ReportParameters.empty().canonical()).isEmpty();
    }

    @Test
    void definitionsCheckTheirKeysAndColumns() {
        List<ReportColumn> columns =
                List.of(ReportColumn.text("code", "Code").asSortable(), ReportColumn.amount("net", "Net"));
        ReportDefinition definition = new ReportDefinition(
                "x",
                "X",
                "sales",
                "",
                List.of("p"),
                ReportSourceKind.VIEWS,
                List.of(ReportParameter.flag("f", "")),
                columns,
                List.of(ReportSort.desc("net")),
                List.of("code"));
        assertThat(definition.hasTotals()).isTrue();
        assertThat(definition.column("code")).isPresent();
        assertThat(definition.parameter("f")).isPresent();
        assertThat(definition.parameter("g")).isEmpty();
        assertThat(ReportSort.desc("net").canonical()).isEqualTo("-net");
        assertThat(ReportSort.asc("net").canonical()).isEqualTo("net");
        assertThatThrownBy(() -> new ReportDefinition(
                        "x",
                        "X",
                        "sales",
                        "",
                        List.of("p"),
                        ReportSourceKind.VIEWS,
                        List.of(),
                        columns,
                        List.of(),
                        List.of("nope")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportDefinition(
                        "x",
                        "X",
                        "sales",
                        "",
                        List.of("p"),
                        ReportSourceKind.VIEWS,
                        List.of(),
                        columns,
                        List.of(ReportSort.asc("nope")),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportColumn("t", "T", ColumnType.TEXT, true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ReportColumn.decimal("d", "D").withTotal().total()).isTrue();
        assertThat(ReportColumn.amount("a", "A").noTotal().total()).isFalse();
        assertThat(ColumnType.DATE.numeric()).isFalse();
        assertThat(ExportFormat.XLSX.extension()).isEqualTo("xlsx");
        assertThat(ExportFormat.CSV.contentType()).isEqualTo("text/csv");
    }
}
