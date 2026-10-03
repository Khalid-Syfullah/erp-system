package com.erp.platform.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Port: does the actor have a valid role assignment in the company (SECURITY.md §4.4 layer 2)?
 * Implemented by Auth. Empty means "not visible" and is answered with 404.
 */
public interface CompanyAccessResolver {

    Optional<CompanyAccess> resolve(AuthenticatedActor actor, UUID companyId);
}
