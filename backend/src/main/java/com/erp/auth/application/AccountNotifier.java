package com.erp.auth.application;

import java.time.OffsetDateTime;

/**
 * Account emails (invitations, password resets, security notices). Called after commit only (see
 * {@code AfterCommit}); implementations must not throw for delivery problems. Links carry the token
 * in the URL fragment, so it never reaches server logs or Referer headers.
 */
public interface AccountNotifier {

    void sendInvitation(String email, String displayName, String link, OffsetDateTime expiresAt);

    void sendPasswordReset(String email, String displayName, String link, OffsetDateTime expiresAt);

    void sendPasswordChanged(String email, String displayName);

    void sendAccountLocked(String email, String displayName, boolean untilAdministratorUnlocks);
}
