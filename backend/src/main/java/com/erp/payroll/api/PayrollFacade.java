package com.erp.payroll.api;

import java.util.Optional;
import java.util.UUID;

/** What Accounting reads from Payroll: pay components, to validate component-scoped account mappings. */
public interface PayrollFacade {

    /** A pay component of the current company. */
    record ComponentInfo(UUID id, String code, String name, String kind, boolean active) {}

    Optional<ComponentInfo> component(UUID componentId);
}
