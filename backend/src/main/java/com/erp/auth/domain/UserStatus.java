package com.erp.auth.domain;

/** Account lifecycle (PRODUCT_SPEC.md §3.2). */
public enum UserStatus {
    INVITED,
    ACTIVE,
    LOCKED,
    DISABLED
}
