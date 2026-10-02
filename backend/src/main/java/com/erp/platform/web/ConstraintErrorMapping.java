package com.erp.platform.web;

import java.util.Map;

/**
 * Contributed by modules to map their database constraint names to specific error codes
 * (DATABASE.md §2.4), e.g. {@code uq_companies__code -> DUPLICATE_CODE}. Constraints without a
 * mapping fall back to the generic code for their SQLSTATE.
 */
public interface ConstraintErrorMapping {

    Map<String, ErrorCode> constraintErrors();
}
