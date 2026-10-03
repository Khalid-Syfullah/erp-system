/**
 * Auth module (ARCHITECTURE.md §4.1, SECURITY.md §3–§4): identities, credentials, browser sessions,
 * MFA, API tokens, roles, permissions and role assignments. Implements the platform security ports
 * ({@code RequestAuthenticator}, {@code CompanyAccessResolver}, {@code PermissionCheck}).
 */
@ApplicationModule(
        displayName = "Auth",
        allowedDependencies = {"platform", "db", "org :: api"})
package com.erp.auth;

import org.springframework.modulith.ApplicationModule;
