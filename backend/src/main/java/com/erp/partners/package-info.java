/**
 * Partners module (ARCHITECTURE.md §4.1, ADR-007): the shared identity of customers and suppliers
 * (code, names, tax registration, addresses, contacts, encrypted bank accounts), partner groups and
 * the supplier profile used by Procurement. Customer profiles follow with Sales (Phase 7).
 */
@ApplicationModule(
        displayName = "Partners",
        allowedDependencies = {"platform", "db", "org :: api"})
package com.erp.partners;

import org.springframework.modulith.ApplicationModule;
