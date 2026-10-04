package com.erp.accounting.application;

import com.erp.accounting.domain.MappingKey;
import com.erp.accounting.domain.MappingKey.ScopeType;
import com.erp.accounting.persistence.EntryRepository;
import com.erp.accounting.persistence.OpenItemRepository;
import com.erp.inventory.events.StockMovementPosted;
import com.erp.platform.context.CurrentContext;
import com.erp.procurement.events.SupplierBillPosted;
import com.erp.sales.events.InvoicePosted;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Books the operational modules' posting events (PRODUCT_SPEC.md §8.6, ADR-005): synchronously, in
 * the publisher's transaction, so the document and its journal entry commit together or not at all
 * (a missing mapping or a closed period rolls the document back). Each event is booked once: its
 * ID is the entry's {@code source_event_id} (ACC-5), and a document has at most one system entry.
 * Credit notes and debit notes post the mirror image of their invoice or bill.
 */
@Component
class OperationalPostings {

    static final String INVENTORY = "inventory";
    static final String STOCK_MOVEMENT = "STOCK_MOVEMENT";

    private final PostingService engine;
    private final AccountDetermination determination;
    private final EntryRepository entries;
    private final AccountingContext context;

    OperationalPostings(
            PostingService engine,
            AccountDetermination determination,
            EntryRepository entries,
            AccountingContext context) {
        this.engine = engine;
        this.determination = determination;
        this.entries = entries;
        this.context = context;
    }

    // ------------------------------------------------------------------------------ inventory

    @EventListener
    void on(StockMovementPosted event) {
        UUID companyId = CurrentContext.requireCompany();
        if (entries.findByEvent(companyId, event.metadata().eventId()).isPresent()) {
            return;
        }
        PostingService.Source source = new PostingService.Source(
                INVENTORY,
                STOCK_MOVEMENT,
                event.movementId(),
                event.number(),
                event.metadata().eventId());
        if ("REVERSAL".equals(event.movementType())) {
            if (event.reversalOfId() != null) {
                entries.lockSystemEntry(companyId, INVENTORY, STOCK_MOVEMENT, event.reversalOfId())
                        .ifPresent(original -> engine.reverse(
                                original, event.accountingDate(), "Reversal of " + original.description(), source));
            }
            return;
        }
        String base = context.profile().baseCurrency();
        boolean transfer = event.movementType().startsWith("TRANSFER");
        List<PostingService.Line> lines = new ArrayList<>();
        Map<UUID, BigDecimal> transferNet = new LinkedHashMap<>();
        for (StockMovementPosted.Line line : event.lines()) {
            BigDecimal value = line.valueBase();
            if (value.signum() == 0 && line.referenceValueBase() == null) {
                continue;
            }
            UUID inventory = determination.resolve(
                    MappingKey.INVENTORY_ASSET,
                    determination.categoryThenWarehouse(line.categoryId(), line.warehouseId()));
            if (transfer) {
                transferNet.merge(inventory, value, BigDecimal::add);
                continue;
            }
            lines.add(PostingService.Line.base(inventory, value, base).withDimensions(line.branchId(), null, null));
            switch (event.movementType()) {
                case "OPENING" -> lines.add(counter(MappingKey.INVENTORY_OPENING, List.of(), value, base, line));
                case "PURCHASE_RECEIPT" -> lines.add(counter(MappingKey.GRNI, List.of(), value, base, line));
                case "PURCHASE_RETURN" -> {
                    // GRNI is cleared at the original receipt value; the difference to the average
                    // cost that left the stock is a purchase price variance.
                    BigDecimal reference = line.referenceValueBase() == null ? value : line.referenceValueBase();
                    lines.add(counter(MappingKey.GRNI, List.of(), reference, base, line));
                    lines.add(PostingService.Line.base(
                                    determination.resolve(
                                            MappingKey.PURCHASE_PRICE_VARIANCE,
                                            determination.category(line.categoryId())),
                                    reference.subtract(value),
                                    base)
                            .withDimensions(line.branchId(), null, null));
                }
                case "SALES_ISSUE", "SALES_RETURN" ->
                    lines.add(counter(MappingKey.COGS, determination.category(line.categoryId()), value, base, line));
                default ->
                    lines.add(counter(
                            MappingKey.INVENTORY_ADJUSTMENT,
                            AccountDetermination.of(ScopeType.REASON_CODE, event.reasonCodeId()),
                            value,
                            base,
                            line));
            }
        }
        // A transfer only moves value between inventory accounts: no entry when they are the same.
        transferNet.forEach((account, net) -> lines.add(PostingService.Line.base(account, net, base)));
        if (lines.stream().allMatch(l -> l.base().signum() == 0)) {
            return;
        }
        engine.post(new PostingService.Request(
                ChartTemplate.Journals.INVENTORY,
                event.accountingDate(),
                "SYSTEM",
                "Stock movement " + event.number() + " (" + event.movementType() + ")",
                base,
                BigDecimal.ONE,
                source,
                null,
                lines,
                null,
                true,
                false));
    }

    /** The opposite side of an inventory line on the account of {@code key}. */
    private PostingService.Line counter(
            MappingKey key,
            List<AccountDetermination.Scope> scopes,
            BigDecimal inventoryValue,
            String base,
            StockMovementPosted.Line line) {
        return PostingService.Line.base(determination.resolve(key, scopes), inventoryValue.negate(), base)
                .withDimensions(line.branchId(), null, null);
    }

    // ---------------------------------------------------------------------------- purchasing

    @EventListener
    void on(SupplierBillPosted event) {
        UUID companyId = CurrentContext.requireCompany();
        if (entries.findByEvent(companyId, event.metadata().eventId()).isPresent()) {
            return;
        }
        boolean debitNote =
                SupplierBillPosted.DEBIT_NOTE_TYPE.equals(event.metadata().eventType());
        BigDecimal sign = debitNote ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        String base = context.profile().baseCurrency();
        String currency = event.currencyCode();
        List<PostingService.Line> lines = new ArrayList<>();
        for (SupplierBillPosted.Line line : event.lines()) {
            if ("STOCK_RECEIVED".equals(line.type())) {
                BigDecimal receiptValue = line.receiptValueBase() == null ? line.netBase() : line.receiptValueBase();
                lines.add(PostingService.Line.base(
                                determination.resolve(MappingKey.GRNI), receiptValue.multiply(sign), base)
                        .withDimensions(line.branchId(), line.departmentId(), null));
                lines.add(PostingService.Line.base(
                                determination.resolve(
                                        MappingKey.PURCHASE_PRICE_VARIANCE, determination.category(line.categoryId())),
                                line.netBase().subtract(receiptValue).multiply(sign),
                                base)
                        .withDimensions(line.branchId(), line.departmentId(), null));
            } else {
                lines.add(new PostingService.Line(
                        determination.resolve(MappingKey.PURCHASE_EXPENSE, determination.category(line.categoryId())),
                        line.netBase().multiply(sign),
                        currency,
                        line.netDoc().multiply(sign),
                        null,
                        line.branchId(),
                        line.departmentId(),
                        line.taxCodeId(),
                        null,
                        false,
                        null));
            }
        }
        for (SupplierBillPosted.TaxLine tax : event.taxLines()) {
            lines.add(new PostingService.Line(
                    determination.resolve(
                            MappingKey.TAX_INPUT, AccountDetermination.of(ScopeType.TAX_CODE, tax.taxCodeId())),
                    tax.taxBase().multiply(sign),
                    currency,
                    tax.taxDoc().multiply(sign),
                    null,
                    null,
                    null,
                    tax.taxCodeId(),
                    null,
                    false,
                    null));
        }
        UUID payables = determination.resolve(
                MappingKey.AP_CONTROL, AccountDetermination.of(ScopeType.PARTNER_GROUP, event.supplierGroupId()));
        BigDecimal total = event.totals().total().multiply(sign);
        BigDecimal totalBase = event.totals().totalBase().multiply(sign);
        lines.add(new PostingService.Line(
                        payables,
                        totalBase.negate(),
                        currency,
                        total.negate(),
                        event.supplierId(),
                        null,
                        null,
                        null,
                        event.supplierInvoiceNumber(),
                        false,
                        null)
                .linkedToNewOpenItem());
        String type = debitNote ? "DEBIT_NOTE" : "BILL";
        engine.post(new PostingService.Request(
                ChartTemplate.Journals.PURCHASES,
                event.accountingDate(),
                "SYSTEM",
                (debitNote ? "Debit note " : "Supplier bill ") + event.number() + " (" + event.supplierInvoiceNumber()
                        + ")",
                currency,
                event.exchangeRate(),
                new PostingService.Source(
                        "procurement",
                        type,
                        event.billId(),
                        event.number(),
                        event.metadata().eventId()),
                null,
                lines,
                new OpenItemRepository.NewItem(
                        "PAYABLE",
                        event.supplierId(),
                        payables,
                        "procurement",
                        type,
                        event.billId(),
                        event.number(),
                        event.documentDate(),
                        debitNote ? event.documentDate() : event.dueDate(),
                        currency,
                        total,
                        totalBase,
                        event.exchangeRate()),
                true,
                false));
    }

    // --------------------------------------------------------------------------------- sales

    @EventListener
    void on(InvoicePosted event) {
        UUID companyId = CurrentContext.requireCompany();
        if (entries.findByEvent(companyId, event.metadata().eventId()).isPresent()) {
            return;
        }
        boolean creditNote =
                InvoicePosted.CREDIT_NOTE_TYPE.equals(event.metadata().eventType());
        BigDecimal sign = creditNote ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        String currency = event.currencyCode();
        UUID receivables = determination.resolve(
                MappingKey.AR_CONTROL, AccountDetermination.of(ScopeType.PARTNER_GROUP, event.customerGroupId()));
        BigDecimal total = event.totals().total().multiply(sign);
        BigDecimal totalBase = event.totals().totalBase().multiply(sign);
        List<PostingService.Line> lines = new ArrayList<>();
        lines.add(new PostingService.Line(
                        receivables,
                        totalBase,
                        currency,
                        total,
                        event.customerId(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        null)
                .linkedToNewOpenItem());
        MappingKey revenue = creditNote ? MappingKey.SALES_RETURNS : MappingKey.SALES_REVENUE;
        for (InvoicePosted.Line line : event.lines()) {
            lines.add(new PostingService.Line(
                    determination.resolve(revenue, determination.category(line.categoryId())),
                    line.netBase().multiply(sign).negate(),
                    currency,
                    line.netDoc().multiply(sign).negate(),
                    null,
                    line.branchId(),
                    line.departmentId(),
                    line.taxCodeId(),
                    null,
                    false,
                    null));
        }
        for (InvoicePosted.TaxLine tax : event.taxLines()) {
            lines.add(new PostingService.Line(
                    determination.resolve(
                            MappingKey.TAX_OUTPUT, AccountDetermination.of(ScopeType.TAX_CODE, tax.taxCodeId())),
                    tax.taxBase().multiply(sign).negate(),
                    currency,
                    tax.taxDoc().multiply(sign).negate(),
                    null,
                    null,
                    null,
                    tax.taxCodeId(),
                    null,
                    false,
                    null));
        }
        String type = creditNote ? "CREDIT_NOTE" : "INVOICE";
        engine.post(new PostingService.Request(
                ChartTemplate.Journals.SALES,
                event.accountingDate(),
                "SYSTEM",
                (creditNote ? "Credit note " : "Invoice ") + event.number(),
                currency,
                event.exchangeRate(),
                new PostingService.Source(
                        "sales",
                        type,
                        event.invoiceId(),
                        event.number(),
                        event.metadata().eventId()),
                null,
                lines,
                new OpenItemRepository.NewItem(
                        "RECEIVABLE",
                        event.customerId(),
                        receivables,
                        "sales",
                        type,
                        event.invoiceId(),
                        event.number(),
                        event.documentDate(),
                        creditNote ? event.documentDate() : event.dueDate(),
                        currency,
                        total,
                        totalBase,
                        event.exchangeRate()),
                true,
                false));
    }
}
