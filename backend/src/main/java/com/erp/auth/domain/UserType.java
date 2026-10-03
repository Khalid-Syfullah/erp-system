package com.erp.auth.domain;

/** Human users log in with a password; service accounts only use API tokens (SECURITY.md §3.1). */
public enum UserType {
    HUMAN,
    SERVICE
}
