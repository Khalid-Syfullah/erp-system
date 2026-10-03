package com.erp.platform.security;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The actor's access to one company: proof of membership plus the branch scope.
 *
 * @param branchScope branches the actor may see; {@code null} means all branches of the company
 */
public record CompanyAccess(UUID companyId, @Nullable Set<UUID> branchScope) {

    public CompanyAccess {
        branchScope = branchScope == null ? null : Set.copyOf(branchScope);
    }
}
