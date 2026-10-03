package com.erp.sales.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.LIKE;
import static com.erp.platform.web.paging.FilterOperator.LTE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the Sales endpoints (API.md §17.7). */
public final class SalesListings {

    public static final ListDefinition PRICE_LISTS = ListDefinition.builder("sales.price_lists")
            .sortable("code", "name")
            .defaultSort(SortOrder.asc("code"))
            .filter("currencyCode", ValueType.STRING, EQ, IN)
            .filter("customerGroupId", ValueType.UUID, EQ)
            .filter("isDefault", ValueType.BOOLEAN, EQ)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .searchable()
            .build();

    public static final ListDefinition PRICE_LIST_ITEMS = ListDefinition.builder("sales.price_list_items")
            .sortable("minQuantity", "createdAt")
            .defaultSort(SortOrder.asc("createdAt"))
            .filter("variantId", ValueType.UUID, EQ, IN)
            .build();

    public static final ListDefinition QUOTATIONS = ListDefinition.builder("sales.quotations")
            .sortable("quotationDate", "validUntil", "createdAt", "total")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("status", Set.of("DRAFT", "SENT", "ACCEPTED", "REJECTED", "EXPIRED", "CANCELLED"), EQ, IN)
            .filter("customerId", ValueType.UUID, EQ, IN)
            .filter("branchId", ValueType.UUID, EQ, IN)
            .filter("quotationDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("validUntil", ValueType.DATE, EQ, GTE, LTE)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    public static final ListDefinition ORDERS = ListDefinition.builder("sales.sales_orders")
            .sortable("orderDate", "createdAt", "total")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter(
                    "status",
                    Set.of("DRAFT", "CONFIRMED", "PARTIALLY_DELIVERED", "DELIVERED", "CLOSED", "CANCELLED"),
                    EQ,
                    IN)
            .enumFilter("invoiceStatus", Set.of("NOT_INVOICED", "PARTIALLY_INVOICED", "INVOICED"), EQ, IN)
            .filter("customerId", ValueType.UUID, EQ, IN)
            .filter("warehouseId", ValueType.UUID, EQ, IN)
            .filter("branchId", ValueType.UUID, EQ, IN)
            .filter("orderDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    public static final ListDefinition DELIVERIES = ListDefinition.builder("sales.deliveries")
            .sortable("deliveryDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("status", Set.of("DRAFT", "POSTED", "CANCELLED"), EQ, IN)
            .filter("salesOrderId", ValueType.UUID, EQ)
            .filter("customerId", ValueType.UUID, EQ, IN)
            .filter("warehouseId", ValueType.UUID, EQ, IN)
            .filter("deliveryDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    public static final ListDefinition RETURNS = ListDefinition.builder("sales.sales_returns")
            .sortable("returnDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("status", Set.of("DRAFT", "RECEIVED", "CANCELLED"), EQ, IN)
            .filter("deliveryId", ValueType.UUID, EQ)
            .filter("salesOrderId", ValueType.UUID, EQ)
            .filter("customerId", ValueType.UUID, EQ, IN)
            .filter("returnDate", ValueType.DATE, EQ, GTE, LTE)
            .searchable()
            .build();

    public static final ListDefinition INVOICES = ListDefinition.builder("sales.invoices")
            .sortable("invoiceDate", "dueDate", "createdAt", "total")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("documentType", Set.of("INVOICE", "CREDIT_NOTE"), EQ)
            .enumFilter("status", Set.of("DRAFT", "POSTED", "CANCELLED"), EQ, IN)
            .filter("customerId", ValueType.UUID, EQ, IN)
            .filter("salesOrderId", ValueType.UUID, EQ)
            .filter("originalInvoiceId", ValueType.UUID, EQ)
            .filter("invoiceDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("dueDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    private SalesListings() {}
}
