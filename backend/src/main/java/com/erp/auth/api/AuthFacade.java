package com.erp.auth.api;

import java.util.Optional;
import java.util.UUID;

/** What other modules may ask of Auth (ARCHITECTURE.md §7: HR deactivates a terminated employee's user). */
public interface AuthFacade {

    /** A human user, not disabled, with a role assignment in the company. */
    record LinkableUser(UUID id, String email, String displayName) {}

    /** The user, if it can be linked to an employee of the company (self-service). */
    Optional<LinkableUser> linkableUser(UUID userId, UUID companyId);

    /**
     * Disables the user (all sessions and API tokens end at once) because their employment ended. Does
     * nothing for a user that is disabled already; refuses the last active system administrator.
     */
    void deactivateForTermination(UUID userId);
}
