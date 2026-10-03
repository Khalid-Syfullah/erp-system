package com.erp.procurement.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.LIKE;
import static com.erp.platform.web.paging.FilterOperator.LTE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the Procurement endpoints (API.md §17.6). */
public final class ProcurementListings {

    public static final ListDefinition REQUISITIONS = ListDefinition.builder("procurement.purchase_requisitions")
            .sortable("createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter(
                    "status",
                    Set.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "PARTIALLY_ORDERED", "ORDERED", "CANCELLED"),
                    EQ,
                    IN)
            .filter("branchId", ValueType.UUID, EQ, IN)
            .filter("departmentId", ValueType.UUID, EQ)
            .filter("requestedBy", ValueType.UUID, EQ)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    public static final ListDefinition PURCHASE_ORDERS = ListDefinition.builder("procurement.purchase_orders")
            .sortable("orderDate", "createdAt", "total")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter(
                    "status",
                    Set.of(
                            "DRAFT",
                            "PENDING_APPROVAL",
                            "APPROVED",
                            "PARTIALLY_RECEIVED",
                            "RECEIVED",
                            "CLOSED",
                            "CANCELLED"),
                    EQ,
                    IN)
            .enumFilter("billingStatus", Set.of("NOT_BILLED", "PARTIALLY_BILLED", "BILLED"), EQ, IN)
            .filter("supplierId", ValueType.UUID, EQ, IN)
            .filter("warehouseId", ValueType.UUID, EQ, IN)
            .filter("branchId", ValueType.UUID, EQ, IN)
            .filter("orderDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    public static final ListDefinition RECEIPTS = ListDefinition.builder("procurement.goods_receipts")
            .sortable("receiptDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("status", Set.of("DRAFT", "POSTED", "CANCELLED"), EQ, IN)
            .filter("purchaseOrderId", ValueType.UUID, EQ)
            .filter("supplierId", ValueType.UUID, EQ, IN)
            .filter("warehouseId", ValueType.UUID, EQ, IN)
            .filter("receiptDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    public static final ListDefinition RETURNS = ListDefinition.builder("procurement.purchase_returns")
            .sortable("returnDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("status", Set.of("DRAFT", "POSTED", "CANCELLED"), EQ, IN)
            .filter("goodsReceiptId", ValueType.UUID, EQ)
            .filter("purchaseOrderId", ValueType.UUID, EQ)
            .filter("supplierId", ValueType.UUID, EQ, IN)
            .filter("returnDate", ValueType.DATE, EQ, GTE, LTE)
            .searchable()
            .build();

    public static final ListDefinition BILLS = ListDefinition.builder("procurement.supplier_bills")
            .sortable("billDate", "dueDate", "createdAt", "total")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("documentType", Set.of("BILL", "DEBIT_NOTE"), EQ)
            .enumFilter("status", Set.of("DRAFT", "POSTED", "CANCELLED"), EQ, IN)
            .enumFilter("matchStatus", Set.of("NOT_CHECKED", "MATCHED", "EXCEPTION", "OVERRIDDEN"), EQ, IN)
            .filter("supplierId", ValueType.UUID, EQ, IN)
            .filter("purchaseOrderId", ValueType.UUID, EQ)
            .filter("originalBillId", ValueType.UUID, EQ)
            .filter("billDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("dueDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("supplierInvoiceNumber", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    private ProcurementListings() {}
}
