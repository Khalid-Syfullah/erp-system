package com.erp.auth;

import java.util.Set;

/** Permission codes of the Auth module (SECURITY.md §4.2) and the system administrator's global set. */
public final class AuthPermissions {

    public static final String USER_READ = "auth.user.read";
    public static final String USER_MANAGE = "auth.user.manage";
    public static final String ROLE_READ = "auth.role.read";
    public static final String ROLE_MANAGE = "auth.role.manage";
    public static final String ROLE_ASSIGNMENT_MANAGE = "auth.role_assignment.manage";
    public static final String SERVICE_ACCOUNT_MANAGE = "auth.service_account.manage";
    public static final String API_TOKEN_MANAGE_OWN = "auth.api_token.manage_own";

    /**
     * Permissions the system administrator holds on global (non-company) endpoints (SECURITY.md
     * §4.3). The flag grants no company permission: business data needs a role assignment.
     */
    public static final Set<String> SYSTEM_ADMIN_GLOBAL = Set.of(
            USER_READ,
            USER_MANAGE,
            ROLE_READ,
            ROLE_MANAGE,
            ROLE_ASSIGNMENT_MANAGE,
            SERVICE_ACCOUNT_MANAGE,
            API_TOKEN_MANAGE_OWN,
            "admin.audit.read",
            "admin.audit.read_global",
            "admin.settings.manage",
            "admin.system.read",
            "org.company.create");

    /**
     * Permissions that only make sense globally; custom (company) roles may not contain them, and
     * company administrators cannot hand them out.
     */
    public static final Set<String> GLOBAL_ONLY = Set.of(
            USER_READ,
            USER_MANAGE,
            ROLE_READ,
            ROLE_MANAGE,
            SERVICE_ACCOUNT_MANAGE,
            "admin.audit.read_global",
            "admin.settings.manage",
            "admin.system.read",
            "org.company.create");

    private AuthPermissions() {}
}
