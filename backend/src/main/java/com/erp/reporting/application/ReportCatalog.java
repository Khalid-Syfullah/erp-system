package com.erp.reporting.application;

import static com.erp.reporting.domain.ReportColumn.amount;
import static com.erp.reporting.domain.ReportColumn.date;
import static com.erp.reporting.domain.ReportColumn.decimal;
import static com.erp.reporting.domain.ReportColumn.id;
import static com.erp.reporting.domain.ReportColumn.integer;
import static com.erp.reporting.domain.ReportColumn.percent;
import static com.erp.reporting.domain.ReportColumn.quantity;
import static com.erp.reporting.domain.ReportColumn.text;
import static com.erp.reporting.domain.ReportSort.asc;
import static com.erp.reporting.domain.ReportSort.desc;

import com.erp.reporting.ReportingPermissions;
import com.erp.reporting.domain.ReportColumn;
import com.erp.reporting.domain.ReportDefinition;
import com.erp.reporting.domain.ReportParameter;
import com.erp.reporting.domain.ReportSort;
import com.erp.reporting.domain.ReportSourceKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The report catalogue (PRODUCT_SPEC.md §13, API.md §17.11): every report's parameters, columns,
 * default order and permissions. {@code R__seed_reporting_report_definitions.sql} mirrors the codes,
 * names, modules and permissions (ReportCatalogIntegrationTest).
 */
@Component
public class ReportCatalog {

    // Parameter names shared by the queries.
    public static final String FROM = "from";
    public static final String TO = "to";
    public static final String AS_OF = "asOf";
    public static final String GROUP_BY = "groupBy";

    private static final ReportParameter P_FROM = ReportParameter.date(FROM, true, "First date, inclusive");
    private static final ReportParameter P_TO = ReportParameter.date(TO, true, "Last date, inclusive");
    private static final ReportParameter P_OPTIONAL_FROM =
            ReportParameter.date(FROM, false, "First document date, inclusive");
    private static final ReportParameter P_OPTIONAL_TO =
            ReportParameter.date(TO, false, "Last document date, inclusive");
    private static final ReportParameter P_AS_OF =
            ReportParameter.dateDefaultToday(AS_OF, "Reference date (default: the company's business date)");
    private static final ReportParameter P_HISTORIC_AS_OF =
            ReportParameter.date(AS_OF, false, "Figures as of this date from the ledger (default: current)");
    private static final ReportParameter P_BRANCH = ReportParameter.id("branchId", "Only this branch");
    private static final ReportParameter P_CUSTOMER = ReportParameter.id("customerId", "Only this customer");
    private static final ReportParameter P_SUPPLIER = ReportParameter.id("supplierId", "Only this supplier");
    private static final ReportParameter P_PRODUCT = ReportParameter.id("productId", "Only this product's variants");
    private static final ReportParameter P_CATEGORY = ReportParameter.id("categoryId", "Only this product category");
    private static final ReportParameter P_WAREHOUSE = ReportParameter.id("warehouseId", "Only this warehouse");
    private static final ReportParameter P_DEPARTMENT = ReportParameter.id("departmentId", "Only this department");

    private static final List<String> SALES = List.of(ReportingPermissions.SALES_READ);
    private static final List<String> PROCUREMENT = List.of(ReportingPermissions.PROCUREMENT_READ);
    private static final List<String> INVENTORY = List.of(ReportingPermissions.INVENTORY_READ);
    private static final List<String> ACCOUNTING = List.of(ReportingPermissions.ACCOUNTING_REPORT_READ);
    private static final List<String> HR = List.of(ReportingPermissions.HR_READ);

    private static final List<String> MOVEMENT_TYPES = List.of(
            "OPENING",
            "PURCHASE_RECEIPT",
            "PURCHASE_RETURN",
            "SALES_ISSUE",
            "SALES_RETURN",
            "TRANSFER",
            "TRANSFER_SHIP",
            "TRANSFER_RECEIVE",
            "ADJUSTMENT",
            "SCRAP",
            "COUNT_ADJUSTMENT",
            "REVERSAL");

    private final Map<String, ReportDefinition> definitions = new LinkedHashMap<>();

    public ReportCatalog() {
        sales();
        procurement();
        inventory();
        accounting();
        hr();
    }

    public Collection<ReportDefinition> all() {
        return definitions.values();
    }

    public Optional<ReportDefinition> find(String code) {
        return Optional.ofNullable(definitions.get(code));
    }

    // ------------------------------------------------------------------------------- sales

    private void sales() {
        List<ReportParameter> filters = List.of(P_FROM, P_TO, P_BRANCH, P_CUSTOMER, P_PRODUCT, P_CATEGORY);
        add(view("sales-summary", "Sales summary", "sales", SALES)
                .describe("Posted invoices and credit notes of the period, net of credit notes, base currency.")
                .parameters(filters)
                .columns(
                        integer("invoiceCount", "Invoices").noTotal(),
                        integer("creditNoteCount", "Credit notes").noTotal(),
                        integer("customerCount", "Customers").noTotal(),
                        amount("invoicedBase", "Invoiced").noTotal(),
                        amount("creditedBase", "Credited").noTotal(),
                        amount("netSalesBase", "Net sales").noTotal(),
                        amount("taxBase", "Tax").noTotal(),
                        amount("grossBase", "Gross").noTotal()));
        add(view("sales-by-customer", "Sales by customer", "sales", SALES)
                .describe("Net sales (invoices less credit notes) per customer, base currency.")
                .parameters(filters)
                .columns(
                        id("customerId", "Customer ID"),
                        text("customerCode", "Customer code").asSortable(),
                        text("customerName", "Customer").asSortable(),
                        integer("invoiceCount", "Documents"),
                        amount("netSalesBase", "Net sales"),
                        amount("taxBase", "Tax"),
                        amount("grossBase", "Gross"))
                .sort(desc("netSalesBase"))
                .keys("customerId"));
        add(view("sales-by-product", "Sales by product", "sales", SALES)
                .describe("Net quantity and sales per product variant, base unit and base currency.")
                .parameters(filters)
                .columns(
                        id("variantId", "Variant ID"),
                        text("sku", "SKU").asSortable(),
                        text("productName", "Product").asSortable(),
                        text("categoryCode", "Category").asSortable(),
                        text("uomCode", "Unit"),
                        quantity("quantityBase", "Quantity").noTotal(),
                        amount("netSalesBase", "Net sales"),
                        amount("averagePriceBase", "Average price").noTotal())
                .sort(desc("netSalesBase"))
                .keys("variantId"));
        add(view("sales-by-branch", "Sales by branch", "sales", SALES)
                .describe("Net sales per branch of the invoice lines (lines without a branch are grouped).")
                .parameters(filters)
                .columns(
                        id("branchId", "Branch ID"),
                        text("branchCode", "Branch code").asSortable(),
                        text("branchName", "Branch").asSortable(),
                        integer("invoiceCount", "Documents").noTotal(),
                        amount("netSalesBase", "Net sales"),
                        amount("taxBase", "Tax"),
                        amount("grossBase", "Gross"))
                .sort(asc("branchCode"))
                .keys("branchCode"));
        add(view("sales-by-period", "Sales by period", "sales", SALES)
                .describe("Net sales per day, ISO week (starting Monday) or month of the invoice date.")
                .parameters(
                        P_FROM,
                        P_TO,
                        ReportParameter.choice(
                                "granularity", "MONTH", List.of("DAY", "WEEK", "MONTH"), "Period length"),
                        P_BRANCH,
                        P_CUSTOMER,
                        P_PRODUCT,
                        P_CATEGORY)
                .columns(
                        date("periodStart", "Period start").asSortable(),
                        integer("invoiceCount", "Documents"),
                        amount("netSalesBase", "Net sales"),
                        amount("taxBase", "Tax"),
                        amount("grossBase", "Gross"))
                .sort(asc("periodStart"))
                .keys("periodStart"));
        add(view("invoice-status", "Invoice status", "sales", SALES)
                .describe("Invoices and credit notes with their document and payment status. Credit notes are"
                        + " negative; open amounts come from Accounting's open items.")
                .parameters(
                        P_OPTIONAL_FROM,
                        P_OPTIONAL_TO,
                        P_CUSTOMER,
                        ReportParameter.choice(
                                "status", null, List.of("DRAFT", "POSTED", "CANCELLED"), "Document status"),
                        ReportParameter.choice(
                                "paymentStatus",
                                null,
                                List.of("UNPAID", "PARTIALLY_PAID", "OVERDUE", "PAID", "NOT_APPLICABLE"),
                                "Payment status"),
                        ReportParameter.choice(
                                "documentType", null, List.of("INVOICE", "CREDIT_NOTE"), "Document type"),
                        P_AS_OF)
                .columns(
                        id("invoiceId", "Invoice ID"),
                        text("number", "Number"),
                        text("documentType", "Type"),
                        date("invoiceDate", "Date").asSortable(),
                        date("dueDate", "Due date").asSortable(),
                        text("customerCode", "Customer code"),
                        text("customerName", "Customer"),
                        text("status", "Status"),
                        text("currencyCode", "Currency"),
                        amount("total", "Total").noTotal(),
                        amount("totalBase", "Total (base)"),
                        amount("openAmountBase", "Open (base)"),
                        text("paymentStatus", "Payment status"),
                        integer("daysOverdue", "Days overdue").noTotal())
                .sort(desc("invoiceDate"))
                .keys("invoiceId"));
        add(view("payment-status", "Invoice payment status", "sales", SALES)
                .describe("Posted invoices grouped by payment status as of a date (overdue: open and past due).")
                .parameters(P_OPTIONAL_FROM, P_OPTIONAL_TO, P_CUSTOMER, P_AS_OF)
                .columns(
                        text("paymentStatus", "Payment status").asSortable(),
                        integer("invoiceCount", "Invoices"),
                        amount("totalBase", "Total (base)"),
                        amount("openAmountBase", "Open (base)"))
                .sort(asc("paymentStatus"))
                .keys("paymentStatus"));
        add(view("gross-margin", "Gross margin", "sales", SALES)
                .describe("Invoice revenue (net of credit notes) against the cost of goods delivered and returned"
                        + " in the period, per product or category.")
                .parameters(
                        P_FROM,
                        P_TO,
                        ReportParameter.choice(GROUP_BY, "PRODUCT", List.of("PRODUCT", "CATEGORY"), "Grouping"),
                        P_BRANCH,
                        P_PRODUCT,
                        P_CATEGORY)
                .columns(
                        id("groupId", "ID"),
                        text("groupCode", "Code").asSortable(),
                        text("groupName", "Name").asSortable(),
                        quantity("quantityBase", "Quantity invoiced").noTotal(),
                        amount("revenueBase", "Revenue"),
                        amount("cogsBase", "Cost of goods sold"),
                        amount("marginBase", "Gross margin"),
                        percent("marginPercent", "Margin %"))
                .sort(desc("revenueBase"))
                .keys("groupCode"));
        add(view("order-backlog", "Order backlog and uninvoiced deliveries", "sales", SALES)
                .describe("Open sales order lines: quantities still to deliver and delivered but not invoiced.")
                .parameters(P_OPTIONAL_FROM, P_OPTIONAL_TO, P_CUSTOMER, P_BRANCH, P_WAREHOUSE, P_PRODUCT)
                .columns(
                        id("lineId", "Line ID"),
                        text("orderNumber", "Order").asSortable(),
                        date("orderDate", "Order date").asSortable(),
                        date("requestedDate", "Requested date"),
                        text("customerCode", "Customer code"),
                        text("customerName", "Customer"),
                        text("sku", "SKU"),
                        text("productName", "Product"),
                        text("status", "Status"),
                        quantity("quantityBase", "Ordered").noTotal(),
                        quantity("deliveredQuantityBase", "Delivered").noTotal(),
                        quantity("invoicedQuantityBase", "Invoiced").noTotal(),
                        quantity("undeliveredQuantityBase", "To deliver").noTotal(),
                        quantity("uninvoicedDeliveredQuantityBase", "Delivered, not invoiced")
                                .noTotal(),
                        text("currencyCode", "Currency"),
                        amount("undeliveredNetAmount", "To deliver (net)").noTotal())
                .sort(asc("orderDate"))
                .keys("lineId"));
    }

    // ------------------------------------------------------------------------- procurement

    private void procurement() {
        add(view("purchases", "Purchases", "procurement", PROCUREMENT)
                .describe("Posted supplier bills less debit notes per supplier, product, category, branch or"
                        + " month, base currency.")
                .parameters(
                        P_FROM,
                        P_TO,
                        ReportParameter.choice(
                                GROUP_BY,
                                "SUPPLIER",
                                List.of("SUPPLIER", "PRODUCT", "CATEGORY", "BRANCH", "MONTH"),
                                "Grouping"),
                        P_SUPPLIER,
                        P_PRODUCT,
                        P_CATEGORY,
                        P_BRANCH)
                .columns(
                        id("groupId", "ID"),
                        text("groupCode", "Code").asSortable(),
                        text("groupName", "Name").asSortable(),
                        integer("billCount", "Documents").noTotal(),
                        quantity("quantityBase", "Quantity").noTotal(),
                        amount("netBase", "Net"),
                        amount("taxBase", "Tax"),
                        amount("grossBase", "Gross"))
                .sort(desc("netBase"))
                .keys("groupCode"));
        add(view("supplier-analysis", "Supplier analysis", "procurement", PROCUREMENT)
                .describe("Per supplier: orders, receipts, received and billed value, on-time receipts, average"
                        + " lead time and current open payables.")
                .parameters(P_FROM, P_TO, P_SUPPLIER)
                .columns(
                        id("supplierId", "Supplier ID"),
                        text("supplierCode", "Supplier code").asSortable(),
                        text("supplierName", "Supplier").asSortable(),
                        integer("orderCount", "Orders"),
                        integer("receiptCount", "Receipts"),
                        amount("receivedValueBase", "Received value"),
                        amount("billedNetBase", "Billed (net)"),
                        percent("onTimeReceiptPercent", "On-time receipts %"),
                        decimal("averageLeadTimeDays", "Average lead time (days)"),
                        amount("openPayablesBase", "Open payables"))
                .sort(desc("billedNetBase"))
                .keys("supplierId"));
        add(view("purchase-orders", "Purchase orders", "procurement", PROCUREMENT)
                .describe("Submitted purchase orders with receipt and billing progress; openOnly keeps approved"
                        + " orders that are not closed or cancelled.")
                .parameters(
                        P_OPTIONAL_FROM,
                        P_OPTIONAL_TO,
                        ReportParameter.choice(
                                "status",
                                null,
                                List.of(
                                        "PENDING_APPROVAL",
                                        "APPROVED",
                                        "PARTIALLY_RECEIVED",
                                        "RECEIVED",
                                        "CLOSED",
                                        "CANCELLED"),
                                "Order status"),
                        ReportParameter.flag("openOnly", "Only open orders"),
                        P_SUPPLIER,
                        P_BRANCH,
                        P_WAREHOUSE)
                .columns(
                        id("purchaseOrderId", "Order ID"),
                        text("poNumber", "Order").asSortable(),
                        date("orderDate", "Order date").asSortable(),
                        date("expectedDate", "Expected"),
                        text("supplierCode", "Supplier code"),
                        text("supplierName", "Supplier"),
                        text("status", "Status"),
                        text("billingStatus", "Billing"),
                        text("currencyCode", "Currency"),
                        amount("total", "Total").noTotal(),
                        quantity("orderedQuantityBase", "Ordered").noTotal(),
                        quantity("receivedQuantityBase", "Received").noTotal(),
                        quantity("billedQuantityBase", "Billed").noTotal(),
                        percent("receivedPercent", "Received %"),
                        percent("billedPercent", "Billed %"))
                .sort(desc("orderDate"))
                .keys("purchaseOrderId"));
        add(view("receiving", "Receiving", "procurement", PROCUREMENT)
                .describe("Posted goods receipt lines with billing and return progress.")
                .parameters(
                        P_FROM,
                        P_TO,
                        P_SUPPLIER,
                        P_WAREHOUSE,
                        P_BRANCH,
                        P_PRODUCT,
                        ReportParameter.flag("pendingBillingOnly", "Only lines not fully billed"))
                .columns(
                        id("lineId", "Line ID"),
                        text("receiptNumber", "Receipt").asSortable(),
                        date("receiptDate", "Date").asSortable(),
                        text("poNumber", "Order"),
                        text("supplierCode", "Supplier code"),
                        text("supplierName", "Supplier"),
                        text("warehouseCode", "Warehouse"),
                        text("sku", "SKU"),
                        text("productName", "Product"),
                        quantity("quantityBase", "Received").noTotal(),
                        amount("valueBase", "Value"),
                        quantity("billedQuantityBase", "Billed").noTotal(),
                        quantity("returnedQuantityBase", "Returned").noTotal(),
                        quantity("unbilledQuantityBase", "Not billed").noTotal(),
                        amount("grniValueBase", "Not invoiced (value)"))
                .sort(desc("receiptDate"))
                .keys("lineId"));
        add(view("grni", "Goods received not invoiced", "procurement", PROCUREMENT)
                .describe("Receipt lines whose value is not yet cleared by bills or returns; the total equals the"
                        + " GRNI account balance.")
                .parameters(P_SUPPLIER, P_BRANCH, P_WAREHOUSE)
                .columns(
                        id("lineId", "Line ID"),
                        text("receiptNumber", "Receipt").asSortable(),
                        date("receiptDate", "Date").asSortable(),
                        text("poNumber", "Order"),
                        text("supplierCode", "Supplier code"),
                        text("supplierName", "Supplier"),
                        text("sku", "SKU"),
                        text("productName", "Product"),
                        quantity("unbilledQuantityBase", "Not billed").noTotal(),
                        amount("valueBase", "Receipt value"),
                        amount("billedValueBase", "Billed value"),
                        amount("returnedValueBase", "Returned value"),
                        amount("creditedValueBase", "Credited value"),
                        amount("grniValueBase", "GRNI"))
                .sort(asc("receiptDate"))
                .keys("lineId"));
        add(view("outstanding-supplier-bills", "Outstanding supplier bills", "procurement", PROCUREMENT)
                .describe("Posted bills and debit notes with an open amount in Accounting's payables.")
                .parameters(P_SUPPLIER, P_AS_OF, ReportParameter.flag("overdueOnly", "Only past due"))
                .columns(
                        id("billId", "Bill ID"),
                        text("number", "Number").asSortable(),
                        text("supplierInvoiceNumber", "Supplier invoice"),
                        text("documentType", "Type"),
                        date("billDate", "Date").asSortable(),
                        date("dueDate", "Due date").asSortable(),
                        text("supplierCode", "Supplier code"),
                        text("supplierName", "Supplier"),
                        text("currencyCode", "Currency"),
                        amount("total", "Total").noTotal(),
                        amount("openAmount", "Open").noTotal(),
                        amount("openAmountBase", "Open (base)"),
                        integer("daysOverdue", "Days overdue").noTotal())
                .sort(asc("dueDate"))
                .keys("billId"));
    }

    // --------------------------------------------------------------------------- inventory

    private void inventory() {
        add(view("stock-on-hand", "Stock on hand", "inventory", INVENTORY)
                .describe("Quantities per warehouse or location in the base unit; as of a past date from the stock"
                        + " ledger (reservations only for the current state).")
                .parameters(
                        P_HISTORIC_AS_OF,
                        ReportParameter.choice(GROUP_BY, "WAREHOUSE", List.of("WAREHOUSE", "LOCATION"), "Grain"),
                        P_WAREHOUSE,
                        ReportParameter.id("locationId", "Only this location"),
                        P_PRODUCT,
                        P_CATEGORY,
                        ReportParameter.flag("includeZero", "Include zero balances"))
                .columns(
                        id("warehouseId", "Warehouse ID"),
                        text("warehouseCode", "Warehouse").asSortable(),
                        text("locationCode", "Location").asSortable(),
                        id("variantId", "Variant ID"),
                        text("sku", "SKU").asSortable(),
                        text("productName", "Product"),
                        text("categoryCode", "Category"),
                        text("uomCode", "Unit"),
                        quantity("onHand", "On hand").noTotal(),
                        quantity("reserved", "Reserved").noTotal(),
                        quantity("available", "Available").noTotal())
                .sort(asc("warehouseCode"), asc("locationCode"), asc("sku"))
                .keys("warehouseCode", "locationCode", "sku"));
        add(view(
                        "stock-valuation",
                        "Stock valuation",
                        "inventory",
                        List.of(ReportingPermissions.INVENTORY_VALUATION_READ))
                .describe("Quantity and value at the moving average per variant or category; as of a date from the"
                        + " ledger. The total equals the inventory accounts in the general ledger.")
                .parameters(
                        P_HISTORIC_AS_OF,
                        ReportParameter.choice(GROUP_BY, "VARIANT", List.of("VARIANT", "CATEGORY"), "Grouping"),
                        P_PRODUCT,
                        P_CATEGORY)
                .columns(
                        id("groupId", "ID"),
                        text("groupCode", "Code").asSortable(),
                        text("groupName", "Name").asSortable(),
                        text("uomCode", "Unit"),
                        quantity("quantityBase", "Quantity").noTotal(),
                        amount("valueBase", "Value"),
                        amount("averageCostBase", "Average cost").noTotal())
                .sort(asc("groupCode"))
                .keys("groupCode"));
        add(view("stock-movements", "Stock movement summary", "inventory", INVENTORY)
                .describe("Opening quantity, movements by kind and closing quantity per warehouse and variant;"
                        + " reversals count under the kind they reverse.")
                .parameters(P_FROM, P_TO, P_WAREHOUSE, P_PRODUCT, P_CATEGORY)
                .columns(
                        text("warehouseCode", "Warehouse").asSortable(),
                        text("sku", "SKU").asSortable(),
                        text("productName", "Product"),
                        text("uomCode", "Unit"),
                        quantity("openingQuantity", "Opening").noTotal(),
                        quantity("openingEntriesQuantity", "Opening entries").noTotal(),
                        quantity("purchasesQuantity", "Purchases").noTotal(),
                        quantity("salesQuantity", "Sales").noTotal(),
                        quantity("transfersQuantity", "Transfers").noTotal(),
                        quantity("adjustmentsQuantity", "Adjustments").noTotal(),
                        quantity("closingQuantity", "Closing").noTotal())
                .sort(asc("warehouseCode"), asc("sku"))
                .keys("warehouseCode", "sku"));
        add(view("slow-moving", "Slow-moving and non-moving stock", "inventory", INVENTORY)
                .describe("Stock on hand without an outbound movement (other than transfers) in the last N days.")
                .parameters(
                        ReportParameter.integer("days", 90, 1, 3650, "Days without an outbound movement"),
                        P_AS_OF,
                        P_WAREHOUSE,
                        P_CATEGORY)
                .columns(
                        text("warehouseCode", "Warehouse").asSortable(),
                        text("sku", "SKU").asSortable(),
                        text("productName", "Product"),
                        text("categoryCode", "Category"),
                        text("uomCode", "Unit"),
                        quantity("onHand", "On hand").noTotal(),
                        date("lastReceiptDate", "Last receipt"),
                        date("lastIssueDate", "Last issue"),
                        integer("daysSinceLastIssue", "Days since issue").noTotal())
                .sort(asc("warehouseCode"), asc("sku"))
                .keys("warehouseCode", "sku"));
        add(view("warehouse-summary", "Warehouse summary", "inventory", INVENTORY)
                .describe("Per warehouse: SKUs in stock, quantities on hand and reserved, inbound and outbound"
                        + " quantities and movements in the period.")
                .parameters(P_FROM, P_TO, P_WAREHOUSE)
                .columns(
                        id("warehouseId", "Warehouse ID"),
                        text("warehouseCode", "Warehouse").asSortable(),
                        text("warehouseName", "Name").asSortable(),
                        text("branchCode", "Branch"),
                        integer("skuCount", "SKUs in stock"),
                        quantity("onHandQuantity", "On hand").noTotal(),
                        quantity("reservedQuantity", "Reserved").noTotal(),
                        quantity("inboundQuantity", "Inbound").noTotal(),
                        quantity("outboundQuantity", "Outbound").noTotal(),
                        integer("movementCount", "Movements"))
                .sort(asc("warehouseCode"))
                .keys("warehouseCode"));
        add(view("inventory-adjustments", "Inventory adjustments", "inventory", INVENTORY)
                .describe("Posted adjustments, scrap and count differences (and their reversals) with reason codes.")
                .parameters(
                        P_FROM,
                        P_TO,
                        P_WAREHOUSE,
                        P_PRODUCT,
                        ReportParameter.id("reasonCodeId", "Only this reason code"))
                .columns(
                        id("transactionId", "Transaction ID"),
                        date("transactionDate", "Date").asSortable(),
                        text("movementNumber", "Movement").asSortable(),
                        text("movementType", "Type"),
                        text("reasonCode", "Reason"),
                        text("warehouseCode", "Warehouse"),
                        text("locationCode", "Location"),
                        text("sku", "SKU"),
                        text("productName", "Product"),
                        quantity("quantityBase", "Quantity").noTotal(),
                        amount("valueBase", "Value"))
                .sort(desc("transactionDate"))
                .keys("transactionId"));
        add(view("inventory-transactions", "Inventory transaction history", "inventory", INVENTORY)
                .describe("The stock ledger: every posted quantity change with its cost and value.")
                .parameters(
                        P_FROM,
                        P_TO,
                        P_WAREHOUSE,
                        ReportParameter.id("locationId", "Only this location"),
                        P_PRODUCT,
                        P_CATEGORY,
                        ReportParameter.choice("movementType", null, MOVEMENT_TYPES, "Movement type"))
                .columns(
                        id("transactionId", "Transaction ID"),
                        date("transactionDate", "Date").asSortable(),
                        text("movementNumber", "Movement").asSortable(),
                        text("movementType", "Type"),
                        text("sourceType", "Source"),
                        text("sourceNumber", "Source document"),
                        text("warehouseCode", "Warehouse"),
                        text("locationCode", "Location"),
                        text("sku", "SKU"),
                        text("productName", "Product"),
                        quantity("quantityBase", "Quantity").noTotal(),
                        amount("unitCostBase", "Unit cost").noTotal(),
                        amount("valueBase", "Value"))
                .sort(asc("transactionDate"))
                .keys("transactionId"));
    }

    // -------------------------------------------------------------------------- accounting

    private void accounting() {
        add(module("trial-balance", "Trial balance", ACCOUNTING)
                .describe("Opening balance, debits, credits and closing balance per account (Accounting).")
                .parameters(
                        P_FROM,
                        P_TO,
                        P_BRANCH,
                        ReportParameter.flag("includeZero", "Include accounts without" + " balance or movement"))
                .columns(
                        text("accountCode", "Account"),
                        text("accountName", "Name"),
                        text("accountType", "Type"),
                        amount("opening", "Opening").noTotal(),
                        amount("debit", "Debit"),
                        amount("credit", "Credit"),
                        amount("closing", "Closing").noTotal()));
        add(module("general-ledger", "General ledger", ACCOUNTING)
                .describe("The posted lines of an account with opening, running and closing balance (Accounting).")
                .parameters(ReportParameter.requiredId("accountId", "The account"), P_FROM, P_TO)
                .columns(
                        date("entryDate", "Date"),
                        text("entryNumber", "Entry"),
                        text("description", "Description"),
                        text("sourceType", "Source"),
                        text("sourceNumber", "Source document"),
                        amount("debit", "Debit"),
                        amount("credit", "Credit"),
                        amount("balance", "Balance").noTotal()));
        add(module("profit-and-loss", "Income statement", ACCOUNTING)
                .describe("Revenue and expenses of a period, optionally against a comparison period (Accounting).")
                .parameters(
                        P_FROM,
                        P_TO,
                        ReportParameter.date("compareFrom", false, "First date of the comparison period"),
                        ReportParameter.date("compareTo", false, "Last date of the comparison period"),
                        P_BRANCH)
                .columns(
                        text("section", "Section"),
                        text("accountCode", "Account"),
                        text("accountName", "Name"),
                        amount("amount", "Amount").noTotal(),
                        amount("comparison", "Comparison").noTotal()));
        add(module("balance-sheet", "Balance sheet", ACCOUNTING)
                .describe("Assets, liabilities and equity as of a date, with current-year earnings (Accounting).")
                .parameters(P_AS_OF)
                .columns(
                        text("section", "Section"),
                        text("accountCode", "Account"),
                        text("accountName", "Name"),
                        amount("amount", "Amount").noTotal()));
        List<ReportColumn> ageing = List.of(
                text("partnerCode", "Partner code"),
                text("partnerName", "Partner"),
                amount("current", "Current"),
                amount("days1To30", "1–30 days"),
                amount("days31To60", "31–60 days"),
                amount("days61To90", "61–90 days"),
                amount("over90", "Over 90 days"),
                amount("total", "Total"));
        add(module(
                        "ar-ageing",
                        "Accounts receivable ageing",
                        List.of(ReportingPermissions.ACCOUNTING_REPORT_READ, ReportingPermissions.ACCOUNTING_AR_READ))
                .describe("Open receivables by customer and days past due as of a date (Accounting).")
                .parameters(P_AS_OF)
                .columns(ageing));
        add(module(
                        "ap-ageing",
                        "Accounts payable ageing",
                        List.of(ReportingPermissions.ACCOUNTING_REPORT_READ, ReportingPermissions.ACCOUNTING_AP_READ))
                .describe("Open payables by supplier and days past due as of a date (Accounting).")
                .parameters(P_AS_OF)
                .columns(ageing));
        add(module("cash-book", "Cash book", ACCOUNTING)
                .describe("A bank account's posted transactions with running balance and reconciliation marks"
                        + " (Accounting).")
                .parameters(ReportParameter.requiredId("bankAccountId", "The bank account"), P_FROM, P_TO)
                .columns(
                        date("entryDate", "Date"),
                        text("entryNumber", "Entry"),
                        text("description", "Description"),
                        text("sourceType", "Source"),
                        text("sourceNumber", "Source document"),
                        amount("debit", "Debit (base)"),
                        amount("credit", "Credit (base)"),
                        amount("amountCurrency", "Amount").noTotal(),
                        amount("runningBalance", "Balance").noTotal(),
                        text("statementReference", "Statement")));
        add(view("cash-position", "Cash and bank position", "accounting", ACCOUNTING)
                .describe("The general-ledger balance of every company bank account as of a date.")
                .parameters(P_AS_OF)
                .columns(
                        id("bankAccountId", "Bank account ID"),
                        text("bankAccountName", "Bank account").asSortable(),
                        text("accountCode", "GL account").asSortable(),
                        text("currencyCode", "Currency"),
                        amount("balanceCurrency", "Balance").noTotal(),
                        amount("balanceBase", "Balance (base)"))
                .sort(asc("bankAccountName"))
                .keys("bankAccountId"));
        add(view("expenses", "Expenses", "accounting", ACCOUNTING)
                .describe("Posted movements on expense accounts (without year-end closing entries) per account,"
                        + " month, branch or department; the total equals the income statement's expenses.")
                .parameters(
                        P_FROM,
                        P_TO,
                        ReportParameter.choice(
                                GROUP_BY, "ACCOUNT", List.of("ACCOUNT", "MONTH", "BRANCH", "DEPARTMENT"), "Grouping"),
                        ReportParameter.id("accountId", "Only this account"),
                        P_BRANCH,
                        P_DEPARTMENT)
                .columns(
                        text("groupCode", "Code").asSortable(),
                        text("groupName", "Name").asSortable(),
                        amount("debitBase", "Debit"),
                        amount("creditBase", "Credit"),
                        amount("netBase", "Net expense"))
                .sort(asc("groupCode"))
                .keys("groupCode"));
    }

    // --------------------------------------------------------------------------------- hr

    private void hr() {
        add(view("headcount", "Headcount", "hr", HR)
                .describe("Employees employed on a date by the department, branch, position or employment type of"
                        + " their assignment then.")
                .parameters(
                        P_AS_OF,
                        ReportParameter.choice(
                                GROUP_BY,
                                "DEPARTMENT",
                                List.of("DEPARTMENT", "BRANCH", "POSITION", "EMPLOYMENT_TYPE"),
                                "Grouping"),
                        P_BRANCH,
                        P_DEPARTMENT)
                .columns(
                        text("groupCode", "Code").asSortable(),
                        text("groupName", "Name").asSortable(),
                        integer("headcount", "Headcount"),
                        decimal("fte", "FTE").withTotal())
                .sort(asc("groupCode"))
                .keys("groupCode"));
        add(view("turnover", "Hires, terminations and turnover", "hr", HR)
                .describe("Opening and closing headcount, hires and terminations of a period; turnover is"
                        + " terminations over the average headcount.")
                .parameters(
                        P_FROM,
                        P_TO,
                        ReportParameter.choice(GROUP_BY, "DEPARTMENT", List.of("DEPARTMENT", "BRANCH"), "Grouping"),
                        P_BRANCH,
                        P_DEPARTMENT)
                .columns(
                        text("groupCode", "Code").asSortable(),
                        text("groupName", "Name").asSortable(),
                        integer("openingHeadcount", "Opening"),
                        integer("hires", "Hires"),
                        integer("terminations", "Terminations"),
                        integer("closingHeadcount", "Closing"),
                        percent("turnoverPercent", "Turnover %"))
                .sort(asc("groupCode"))
                .keys("groupCode"));
        add(view("attendance", "Attendance", "hr", HR)
                .describe("Recorded attendance days by status and worked minutes per employee and department.")
                .parameters(
                        P_FROM, P_TO, P_BRANCH, P_DEPARTMENT, ReportParameter.id("employeeId", "Only this employee"))
                .columns(
                        id("employeeId", "Employee ID"),
                        text("employeeNumber", "Employee").asSortable(),
                        text("employeeName", "Name").asSortable(),
                        text("departmentCode", "Department").asSortable(),
                        integer("presentDays", "Present"),
                        integer("remoteDays", "Remote"),
                        integer("halfDays", "Half days"),
                        integer("absentDays", "Absent"),
                        integer("leaveDays", "On leave"),
                        integer("holidayDays", "Holidays"),
                        integer("recordedDays", "Recorded days"),
                        integer("workedMinutes", "Worked minutes"))
                .sort(asc("employeeNumber"), asc("departmentCode"))
                .keys("employeeId", "departmentCode"));
        add(view("leave", "Leave taken", "hr", HR)
                .describe("Leave requests starting in the period per employee and leave type (default: approved).")
                .parameters(
                        P_FROM,
                        P_TO,
                        ReportParameter.id("leaveTypeId", "Only this leave type"),
                        ReportParameter.choice(
                                "status",
                                "APPROVED",
                                List.of("SUBMITTED", "APPROVED", "REJECTED", "CANCELLED"),
                                "Request status"),
                        P_BRANCH,
                        P_DEPARTMENT)
                .columns(
                        id("employeeId", "Employee ID"),
                        text("employeeNumber", "Employee").asSortable(),
                        text("employeeName", "Name").asSortable(),
                        text("departmentCode", "Department"),
                        text("leaveTypeCode", "Leave type").asSortable(),
                        text("leaveTypeName", "Leave type name"),
                        ReportColumn.bool("paid", "Paid"),
                        integer("requestCount", "Requests"),
                        decimal("days", "Days").withTotal())
                .sort(asc("employeeNumber"), asc("leaveTypeCode"))
                .keys("employeeId", "leaveTypeCode"));
        add(view("payroll-summary", "Payroll summary", "payroll", List.of(ReportingPermissions.PAYROLL_REPORT_READ))
                .describe("Posted and paid payroll per pay period, department or component: earnings, deductions,"
                        + " employer contributions and net pay. No per-employee figures.")
                .parameters(
                        P_FROM,
                        P_TO,
                        ReportParameter.choice(
                                GROUP_BY, "COMPONENT", List.of("PERIOD", "DEPARTMENT", "COMPONENT"), "Grouping"),
                        P_BRANCH,
                        P_DEPARTMENT)
                .columns(
                        text("groupCode", "Code").asSortable(),
                        text("groupName", "Name").asSortable(),
                        text("kind", "Kind"),
                        amount("earnings", "Earnings"),
                        amount("deductions", "Deductions"),
                        amount("employerContributions", "Employer contributions"),
                        amount("netPay", "Net pay"))
                .sort(asc("groupCode"))
                .keys("groupCode"));
    }

    // ----------------------------------------------------------------------------- builder

    private void add(Builder builder) {
        ReportDefinition definition = builder.build();
        if (definitions.put(definition.code(), definition) != null) {
            throw new IllegalStateException("Duplicate report " + definition.code());
        }
    }

    private static Builder view(String code, String name, String module, List<String> permissions) {
        return new Builder(code, name, module, permissions, ReportSourceKind.VIEWS);
    }

    private static Builder module(String code, String name, List<String> permissions) {
        return new Builder(code, name, "accounting", permissions, ReportSourceKind.MODULE);
    }

    private static final class Builder {
        private final String code;
        private final String name;
        private final String module;
        private final List<String> permissions;
        private final ReportSourceKind source;
        private String description = "";
        private List<ReportParameter> parameters = List.of();
        private List<ReportColumn> columns = List.of();
        private List<ReportSort> sort = List.of();
        private List<String> keys = List.of();

        private Builder(String code, String name, String module, List<String> permissions, ReportSourceKind source) {
            this.code = code;
            this.name = name;
            this.module = module;
            this.permissions = permissions;
            this.source = source;
        }

        Builder describe(String text) {
            this.description = text;
            return this;
        }

        Builder parameters(ReportParameter... values) {
            this.parameters = List.of(values);
            return this;
        }

        Builder parameters(List<ReportParameter> values) {
            this.parameters = values;
            return this;
        }

        Builder columns(ReportColumn... values) {
            this.columns = List.of(values);
            return this;
        }

        Builder columns(List<ReportColumn> values) {
            this.columns = new ArrayList<>(values);
            return this;
        }

        Builder sort(ReportSort... values) {
            this.sort = List.of(values);
            return this;
        }

        Builder keys(String... values) {
            this.keys = List.of(values);
            return this;
        }

        ReportDefinition build() {
            return new ReportDefinition(
                    code, name, module, description, permissions, source, parameters, columns, sort, keys);
        }
    }
}
