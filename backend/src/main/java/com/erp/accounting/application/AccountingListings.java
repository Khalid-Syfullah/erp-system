package com.erp.accounting.application;

import static com.erp.platform.web.paging.FilterOperator.EQ;
import static com.erp.platform.web.paging.FilterOperator.GTE;
import static com.erp.platform.web.paging.FilterOperator.IN;
import static com.erp.platform.web.paging.FilterOperator.LIKE;
import static com.erp.platform.web.paging.FilterOperator.LTE;
import static com.erp.platform.web.paging.FilterOperator.NE;

import com.erp.platform.web.paging.ListDefinition;
import com.erp.platform.web.paging.SortOrder;
import com.erp.platform.web.paging.ValueType;
import java.util.Set;

/** List contracts of the Accounting endpoints (API.md §17.8). */
public final class AccountingListings {

    static final Set<String> TYPES = Set.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE");

    public static final ListDefinition ACCOUNTS = ListDefinition.builder("accounting.accounts")
            .sortable("code", "name")
            .defaultSort(SortOrder.asc("code"))
            .enumFilter("accountType", TYPES, EQ, IN)
            .filter("accountSubtype", ValueType.STRING, EQ, IN)
            .enumFilter("status", Set.of("ACTIVE", "INACTIVE"), EQ)
            .filter("isControl", ValueType.BOOLEAN, EQ)
            .filter("isPostable", ValueType.BOOLEAN, EQ)
            .filter("parentId", ValueType.UUID, EQ)
            .filter("code", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    public static final ListDefinition FISCAL_YEARS = ListDefinition.builder("accounting.fiscal_years")
            .sortable("startDate")
            .defaultSort(SortOrder.desc("startDate"))
            .enumFilter("status", Set.of("OPEN", "CLOSED"), EQ)
            .build();

    public static final ListDefinition PERIODS = ListDefinition.builder("accounting.periods")
            .sortable("startDate")
            .defaultSort(SortOrder.asc("startDate"))
            .enumFilter("status", Set.of("OPEN", "SOFT_CLOSED", "CLOSED"), EQ, IN)
            .filter("fiscalYearId", ValueType.UUID, EQ)
            .filter("startDate", ValueType.DATE, GTE, LTE)
            .build();

    public static final ListDefinition JOURNALS = ListDefinition.builder("accounting.journals")
            .sortable("code")
            .defaultSort(SortOrder.asc("code"))
            .filter("journalType", ValueType.STRING, EQ)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .build();

    public static final ListDefinition JOURNAL_ENTRIES = ListDefinition.builder("accounting.journal_entries")
            .sortable("entryDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("status", Set.of("DRAFT", "POSTED"), EQ)
            .enumFilter("entryType", Set.of("MANUAL", "SYSTEM", "REVERSAL", "OPENING", "CLOSING", "ADJUSTMENT"), EQ, IN)
            .filter("journalId", ValueType.UUID, EQ)
            .filter("periodId", ValueType.UUID, EQ)
            .filter("entryDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("sourceModule", ValueType.STRING, EQ)
            .filter("sourceType", ValueType.STRING, EQ)
            .filter("sourceId", ValueType.UUID, EQ)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    public static final ListDefinition OPEN_ITEMS = ListDefinition.builder("accounting.open_items")
            .sortable("documentDate", "dueDate", "createdAt")
            .defaultSort(SortOrder.asc("dueDate"))
            .enumFilter("kind", Set.of("RECEIVABLE", "PAYABLE"), EQ)
            .enumFilter("status", Set.of("OPEN", "PARTIALLY_SETTLED", "SETTLED", "VOIDED"), EQ, NE, IN)
            .filter("partnerId", ValueType.UUID, EQ, IN)
            .filter("currencyCode", ValueType.STRING, EQ)
            .filter("dueDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("documentDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("sourceModule", ValueType.STRING, EQ)
            .filter("sourceType", ValueType.STRING, EQ)
            .filter("sourceId", ValueType.UUID, EQ)
            .filter("documentNumber", ValueType.STRING, EQ, LIKE)
            .build();

    public static final ListDefinition BANK_ACCOUNTS = ListDefinition.builder("accounting.bank_accounts")
            .sortable("name")
            .defaultSort(SortOrder.asc("name"))
            .filter("currencyCode", ValueType.STRING, EQ)
            .filter("isActive", ValueType.BOOLEAN, EQ)
            .build();

    public static final ListDefinition PAYMENTS = ListDefinition.builder("accounting.payments")
            .sortable("paymentDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("status", Set.of("DRAFT", "POSTED", "VOIDED"), EQ, IN)
            .enumFilter("direction", Set.of("INBOUND", "OUTBOUND"), EQ)
            .filter("partnerId", ValueType.UUID, EQ)
            .filter("bankAccountId", ValueType.UUID, EQ)
            .filter("paymentDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    public static final ListDefinition EXPENSES = ListDefinition.builder("accounting.expenses")
            .sortable("expenseDate", "createdAt")
            .defaultSort(SortOrder.desc("createdAt"))
            .enumFilter("status", Set.of("DRAFT", "POSTED", "REVERSED"), EQ, IN)
            .filter("bankAccountId", ValueType.UUID, EQ)
            .filter("partnerId", ValueType.UUID, EQ)
            .filter("expenseDate", ValueType.DATE, EQ, GTE, LTE)
            .filter("number", ValueType.STRING, EQ, LIKE)
            .searchable()
            .build();

    private AccountingListings() {}
}
