package com.erp.auth.application;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A user's effective access to one company today: union of the permissions of valid assignments and
 * the branch scope ({@code null} = all branches).
 */
public record CompanyGrant(
        UUID companyId, Set<String> permissions, @Nullable Set<UUID> branchScope) {

    public CompanyGrant {
        permissions = Set.copyOf(permissions);
        branchScope = branchScope == null ? null : Set.copyOf(branchScope);
    }
}
