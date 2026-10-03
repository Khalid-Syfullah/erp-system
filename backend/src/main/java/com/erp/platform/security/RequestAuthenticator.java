package com.erp.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

/**
 * Port through which the Auth module authenticates requests (dependency inversion: the platform's
 * filter chain must not depend on Auth). Implementations must not throw for bad credentials; they
 * return empty.
 */
public interface RequestAuthenticator {

    /** Verifies an {@code Authorization: Bearer} API token. */
    Optional<AuthenticatedActor> authenticateBearer(String token, HttpServletRequest request);

    /**
     * Verifies the browser session cookie, if present. May write to the response (e.g. clear a
     * stale cookie).
     */
    Optional<AuthenticatedActor> authenticateSession(HttpServletRequest request, HttpServletResponse response);
}
