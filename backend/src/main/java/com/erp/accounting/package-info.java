/**
 * Accounting module (ARCHITECTURE.md §4.1, PRODUCT_SPEC.md §8): chart of accounts, account
 * determination, fiscal years and periods, journals and the general ledger, AR/AP open items,
 * payments with allocations, expenses, bank accounts and reconciliation marks, period and year close,
 * and the financial reports. Accounting is downstream of the operational modules (ADR-005): it books
 * their published events synchronously, in their transaction, and answers their questions through the
 * ports they define (credit exposure, settlement). Operational modules never post journal entries
 * themselves.
 */
@ApplicationModule(
        displayName = "Accounting",
        allowedDependencies = {
            "platform",
            "db",
            "org :: api",
            "org :: events",
            "partners :: api",
            "inventory :: api",
            "inventory :: events",
            "procurement :: api",
            "procurement :: events",
            "sales :: api",
            "sales :: events",
            "payroll :: api",
            "payroll :: events"
        })
package com.erp.accounting;

import org.springframework.modulith.ApplicationModule;
