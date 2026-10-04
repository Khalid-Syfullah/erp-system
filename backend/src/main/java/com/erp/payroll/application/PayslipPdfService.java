package com.erp.payroll.application;

import com.erp.org.api.OrgFacade;
import com.erp.payroll.persistence.PayslipRepository;
import com.erp.payroll.persistence.PeriodRepository;
import com.erp.payroll.persistence.RunRepository;
import com.erp.platform.files.FileService;
import com.erp.platform.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payslip PDFs (PRODUCT_SPEC.md §11.1, ARCHITECTURE.md §8.6): rendered with OpenPDF once a run is
 * posted, stored as platform files and linked to the payslip. The PDF job renders them in the
 * background; a download of a payslip without one renders it on the spot. Object storage is written
 * outside database transactions.
 */
@Service
public class PayslipPdfService {

    private static final Logger log = LoggerFactory.getLogger(PayslipPdfService.class);
    private static final int BATCH = 50;

    /** Everything a payslip PDF shows. */
    record Snapshot(
            PayrollViews.Payslip payslip,
            List<PayrollViews.PayslipLine> lines,
            PayrollViews.Run run,
            PayrollViews.Period period,
            String companyName,
            @Nullable String departmentName) {}

    private final PayslipRepository payslips;
    private final RunRepository runs;
    private final PeriodRepository periods;
    private final FileService files;
    private final OrgFacade org;
    private final PayrollContext context;
    private final TransactionTemplate tx;

    PayslipPdfService(
            PayslipRepository payslips,
            RunRepository runs,
            PeriodRepository periods,
            FileService files,
            OrgFacade org,
            PayrollContext context,
            TransactionTemplate tx) {
        this.payslips = payslips;
        this.runs = runs;
        this.periods = periods;
        this.files = files;
        this.org = org;
        this.context = context;
        this.tx = tx;
    }

    /** Renders a batch of released payslips without a PDF; returns how many it rendered. */
    public int renderPending() {
        UUID companyId = context.companyId();
        List<UUID> pending = tx.execute(status -> payslips.pendingPdfs(companyId, BATCH));
        int rendered = 0;
        for (UUID id : pending == null ? List.<UUID>of() : pending) {
            try {
                pdf(id);
                rendered++;
            } catch (RuntimeException e) {
                log.error("Rendering payslip {} failed", id, e);
            }
        }
        return rendered;
    }

    /** The released payslip's PDF; rendered and stored on first use. Call outside a transaction. */
    public byte[] pdf(UUID payslipId) {
        Snapshot snapshot = tx.execute(status -> snapshot(payslipId));
        if (snapshot == null) {
            throw ApiException.notFound();
        }
        if (snapshot.payslip().fileId() != null) {
            return files.read(snapshot.payslip().fileId())
                    .orElseThrow(ApiException::notFound)
                    .bytes();
        }
        byte[] bytes = render(snapshot);
        UUID companyId = context.companyId();
        String name = "payslip-" + snapshot.payslip().employeeNumber() + "-"
                + snapshot.run().number() + ".pdf";
        files.attach(
                "payroll",
                "payslip",
                name,
                "application/pdf",
                bytes,
                upload -> tx.execute(status -> {
                    var file = files.register(upload, payslipId);
                    if (!payslips.setFile(companyId, payslipId, file.id())) {
                        // Another worker stored it first: keep theirs.
                        status.setRollbackOnly();
                        files.discard(upload);
                    }
                    return file;
                }));
        return bytes;
    }

    private Snapshot snapshot(UUID payslipId) {
        UUID companyId = context.companyId();
        PayrollViews.Payslip payslip = payslips.find(companyId, payslipId).orElseThrow(ApiException::notFound);
        PayrollViews.Run run = runs.find(companyId, payslip.runId()).orElseThrow();
        if (!run.status().released()) {
            throw new ApiException(
                    PayrollErrorCode.PAYSLIP_NOT_RELEASED, "Payslips are available once the run is posted.");
        }
        PayrollViews.Period period = periods.find(companyId, run.periodId()).orElseThrow();
        String company = org.findCompany(companyId).map(c -> c.displayName()).orElse("");
        String department = org.departmentForUse(companyId, payslip.departmentId())
                .map(d -> d.name())
                .orElse(null);
        return new Snapshot(
                payslip,
                payslips.lines(companyId, List.of(payslipId)).getOrDefault(payslipId, List.of()),
                run,
                period,
                company,
                department);
    }

    /** The PDF: header, employee, earnings, deductions, employer contributions and totals. */
    static byte[] render(Snapshot s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 48, 48, 48, 48);
        PdfWriter.getInstance(document, out);
        document.open();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
        PayrollViews.Payslip p = s.payslip();

        document.add(new Paragraph(s.companyName(), title));
        document.add(new Paragraph(
                "Payslip " + s.run().number() + " · " + s.period().startDate() + " – "
                        + s.period().endDate() + " · paid " + s.period().payDate(),
                normal));
        document.add(new Paragraph(" ", normal));

        PdfPTable employee = new PdfPTable(new float[] {1, 2});
        employee.setWidthPercentage(100);
        row(employee, "Employee", p.employeeName() + " (" + p.employeeNumber() + ")", bold, normal);
        row(employee, "Position", p.positionTitle() == null ? "—" : p.positionTitle(), bold, normal);
        row(employee, "Department", s.departmentName() == null ? "—" : s.departmentName(), bold, normal);
        row(employee, "Days paid", p.daysPaid() + " of " + p.daysInPeriod(), bold, normal);
        document.add(employee);
        document.add(new Paragraph(" ", normal));

        section(document, "Earnings", s.lines(), "EARNING", p.currencyCode(), bold, normal);
        section(document, "Deductions", s.lines(), "DEDUCTION", p.currencyCode(), bold, normal);

        PdfPTable totals = new PdfPTable(new float[] {3, 1});
        totals.setWidthPercentage(100);
        amountRow(totals, "Gross pay", p.grossAmount(), p.currencyCode(), bold);
        amountRow(totals, "Deductions", p.deductionAmount(), p.currencyCode(), bold);
        amountRow(totals, "Net pay", p.netAmount(), p.currencyCode(), title);
        document.add(totals);
        document.add(new Paragraph(" ", normal));
        section(
                document,
                "Employer contributions (not deducted)",
                s.lines(),
                "EMPLOYER_CONTRIBUTION",
                p.currencyCode(),
                bold,
                normal);
        document.close();
        return out.toByteArray();
    }

    private static void section(
            Document document,
            String heading,
            List<PayrollViews.PayslipLine> lines,
            String kind,
            String currency,
            Font bold,
            Font normal) {
        List<PayrollViews.PayslipLine> rows =
                lines.stream().filter(l -> l.kind().equals(kind)).toList();
        if (rows.isEmpty()) {
            return;
        }
        document.add(new Paragraph(heading, bold));
        PdfPTable table = new PdfPTable(new float[] {3, 1});
        table.setWidthPercentage(100);
        for (PayrollViews.PayslipLine l : rows) {
            String label = l.componentName()
                    + (l.quantity() == null
                            ? ""
                            : " (" + l.quantity().stripTrailingZeros().toPlainString() + " × "
                                    + (l.rate() == null
                                            ? ""
                                            : l.rate().stripTrailingZeros().toPlainString()) + ")");
            amountRow(table, label, l.amount(), currency, normal);
        }
        document.add(table);
        document.add(new Paragraph(" ", normal));
    }

    private static void row(PdfPTable table, String label, String value, Font bold, Font normal) {
        table.addCell(cell(new Phrase(label, bold), Element.ALIGN_LEFT));
        table.addCell(cell(new Phrase(value, normal), Element.ALIGN_LEFT));
    }

    private static void amountRow(PdfPTable table, String label, BigDecimal amount, String currency, Font font) {
        table.addCell(cell(new Phrase(label, font), Element.ALIGN_LEFT));
        table.addCell(cell(new Phrase(amount.toPlainString() + " " + currency, font), Element.ALIGN_RIGHT));
    }

    private static PdfPCell cell(Phrase phrase, int alignment) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(4);
        return cell;
    }
}
