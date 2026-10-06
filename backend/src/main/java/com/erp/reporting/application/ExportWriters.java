package com.erp.reporting.application;

import com.erp.reporting.domain.ColumnType;
import com.erp.reporting.domain.ExportFormat;
import com.erp.reporting.domain.ReportColumn;
import com.erp.reporting.domain.SpreadsheetText;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.jspecify.annotations.Nullable;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Streaming writers of report exports. Rows arrive one by one and go straight to a file on local
 * disk; only the current batch is held in memory (XLSX worksheets and PDF tables are flushed every
 * {@value #FLUSH_EVERY} rows). XLSX and PDF end with a totals row over the numeric columns that have
 * totals; CSV holds the data only. Text written to CSV is neutralised against formula injection;
 * XLSX cells are typed (strings are never formulas).
 */
public final class ExportWriters {

    static final int FLUSH_EVERY = 1000;

    private ExportWriters() {}

    /** What an export file says about itself (title block of XLSX and PDF). */
    public record Heading(String title, String company, Map<String, String> parameters, OffsetDateTime generatedAt) {}

    /** A writer of one export file. */
    public interface ExportWriter extends RowSink, Closeable {}

    public static ExportWriter open(ExportFormat format, Path file, List<ReportColumn> columns, Heading heading)
            throws IOException {
        return switch (format) {
            case CSV -> new Csv(file, columns);
            case XLSX -> new Xlsx(file, columns, heading);
            case PDF -> new Pdf(file, columns, heading);
        };
    }

    /** Text of a value for CSV and PDF: plain decimals, ISO dates. */
    static String text(@Nullable Object value) {
        return switch (value) {
            case null -> "";
            case BigDecimal decimal -> decimal.toPlainString();
            case LocalDate date -> date.toString();
            default -> value.toString();
        };
    }

    // ------------------------------------------------------------------------------------ CSV

    private static final class Csv implements ExportWriter {

        private final Writer out;

        Csv(Path file, List<ReportColumn> columns) throws IOException {
            this.out = new BufferedWriter(
                    new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8), 64 * 1024);
            // A byte order mark, so spreadsheet applications read the file as UTF-8.
            out.write('﻿');
            line(columns.stream().map(ReportColumn::label).toArray());
        }

        @Override
        public void row(@Nullable Object[] values) throws IOException {
            line(values);
        }

        private void line(@Nullable Object[] values) throws IOException {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    out.write(',');
                }
                Object value = values[i];
                out.write(value instanceof String s ? SpreadsheetText.csv(s) : SpreadsheetText.csv(text(value)));
            }
            out.write("\r\n");
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    // ----------------------------------------------------------------------------------- XLSX

    private static final class Xlsx implements ExportWriter {

        private final OutputStream stream;
        private final Workbook workbook;
        private final Worksheet sheet;
        private final List<ReportColumn> columns;
        private final Totals totals;
        private int row;

        Xlsx(Path file, List<ReportColumn> columns, Heading heading) throws IOException {
            this.stream = new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024);
            this.workbook = new Workbook(stream, "ERP", "1.0");
            this.sheet = workbook.newWorksheet(sheetName(heading.title()));
            this.columns = columns;
            this.totals = new Totals(columns);
            sheet.inlineString(0, 0, heading.title());
            sheet.style(0, 0).bold().fontSize(14).set();
            sheet.inlineString(1, 0, heading.company() + " · " + describe(heading));
            row = 3;
            for (int c = 0; c < columns.size(); c++) {
                sheet.inlineString(row, c, columns.get(c).label());
                sheet.style(row, c).bold().fillColor("EEEEEE").set();
                sheet.width(c, columns.get(c).type().numeric() ? 16 : 24);
            }
            sheet.freezePane(0, row + 1);
            row++;
        }

        @Override
        public void row(@Nullable Object[] values) throws IOException {
            totals.add(values);
            write(values, false);
        }

        private void write(@Nullable Object[] values, boolean bold) throws IOException {
            for (int c = 0; c < values.length; c++) {
                Object value = values[c];
                switch (value) {
                    case null -> {}
                    case Number number -> {
                        sheet.value(row, c, number);
                        sheet.style(row, c)
                                .format(format(columns.get(c).type()))
                                .set();
                    }
                    case LocalDate date -> {
                        sheet.value(row, c, date);
                        sheet.style(row, c).format("yyyy-mm-dd").set();
                    }
                    case Boolean flag -> sheet.value(row, c, flag);
                    default -> sheet.inlineString(row, c, value.toString());
                }
                if (bold && value != null) {
                    sheet.style(row, c).bold().set();
                }
            }
            row++;
            if (row % FLUSH_EVERY == 0) {
                sheet.flush();
            }
        }

        @Override
        public void close() throws IOException {
            if (totals.any()) {
                write(totals.row("Total"), true);
            }
            sheet.finish();
            workbook.finish();
            stream.close();
        }

        private static String format(ColumnType type) {
            return switch (type) {
                case INTEGER -> "0";
                case QUANTITY -> "#,##0.######";
                case PERCENT, DECIMAL -> "#,##0.00";
                default -> "#,##0.00##";
            };
        }

        /** Worksheet names: at most 31 characters, none of {@code : \ / ? * [ ]}. */
        private static String sheetName(String title) {
            String name = title.replaceAll("[:\\\\/?*\\[\\]]", " ").strip();
            return name.isEmpty() ? "Report" : name.substring(0, Math.min(31, name.length()));
        }
    }

    // ------------------------------------------------------------------------------------ PDF

    private static final class Pdf implements ExportWriter {

        private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7);
        private static final Font HEADER = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7);
        private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7);

        private final OutputStream stream;
        private final Document document;
        private final PdfPTable table;
        private final List<ReportColumn> columns;
        private final Totals totals;
        private int pending;

        Pdf(Path file, List<ReportColumn> columns, Heading heading) throws IOException {
            this.stream = new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024);
            this.columns = columns;
            this.totals = new Totals(columns);
            this.document = new Document(columns.size() > 6 ? PageSize.A4.rotate() : PageSize.A4, 28, 28, 32, 32);
            try {
                PdfWriter.getInstance(document, stream);
                document.addTitle(heading.title());
                document.open();
                document.add(new Paragraph(heading.title(), TITLE));
                document.add(new Paragraph(heading.company() + " · " + describe(heading), SMALL));
                document.add(new Paragraph(" ", SMALL));
                table = new PdfPTable(columns.size());
                table.setWidthPercentage(100);
                table.setHeaderRows(1);
                // Large tables are written in parts instead of being kept in memory.
                table.setComplete(false);
                for (ReportColumn column : columns) {
                    PdfPCell cell = new PdfPCell(new Phrase(column.label(), HEADER));
                    cell.setBackgroundColor(new java.awt.Color(0xEE, 0xEE, 0xEE));
                    cell.setHorizontalAlignment(column.type().numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                    table.addCell(cell);
                }
            } catch (DocumentException e) {
                throw new IOException(e);
            }
        }

        @Override
        public void row(@Nullable Object[] values) throws IOException {
            totals.add(values);
            write(values, SMALL);
        }

        private void write(@Nullable Object[] values, Font font) throws IOException {
            for (int c = 0; c < values.length; c++) {
                PdfPCell cell = new PdfPCell(new Phrase(text(values[c]), font));
                cell.setHorizontalAlignment(columns.get(c).type().numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                table.addCell(cell);
            }
            if (++pending >= FLUSH_EVERY) {
                flush();
            }
        }

        private void flush() throws IOException {
            try {
                document.add(table);
                pending = 0;
            } catch (DocumentException e) {
                throw new IOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                if (totals.any()) {
                    write(totals.row("Total"), BOLD);
                }
                table.setComplete(true);
                document.add(table);
                document.close();
            } catch (DocumentException e) {
                throw new IOException(e);
            } finally {
                stream.close();
            }
        }
    }

    // --------------------------------------------------------------------------------- shared

    private static String describe(Heading heading) {
        String parameters = heading.parameters().entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        return "Generated "
                + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        heading.generatedAt().withNano(0)) + (parameters.isEmpty() ? "" : " · " + parameters);
    }

    /** Sums of the columns that have totals, accumulated while streaming. */
    private static final class Totals {

        private final List<ReportColumn> columns;
        private final BigDecimal[] sums;

        Totals(List<ReportColumn> columns) {
            this.columns = columns;
            this.sums = new BigDecimal[columns.size()];
            for (int i = 0; i < sums.length; i++) {
                sums[i] = columns.get(i).total() ? BigDecimal.ZERO : null;
            }
        }

        boolean any() {
            return columns.stream().anyMatch(ReportColumn::total);
        }

        void add(@Nullable Object[] values) {
            for (int i = 0; i < values.length; i++) {
                if (sums[i] != null && values[i] instanceof Number number) {
                    sums[i] = sums[i].add(number instanceof BigDecimal d ? d : new BigDecimal(number.toString()));
                }
            }
        }

        @Nullable Object[] row(String label) {
            Object[] row = new Object[sums.length];
            for (int i = 0; i < sums.length; i++) {
                row[i] = sums[i];
            }
            if (row.length > 0 && row[0] == null) {
                row[0] = label;
            }
            return row;
        }
    }
}
