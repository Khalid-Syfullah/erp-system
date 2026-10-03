package com.erp.platform.security;

import com.erp.platform.web.ApiException;
import com.erp.platform.web.PlatformErrorCode;
import java.time.Duration;
import java.time.Instant;

/**
 * Step-up re-authentication (SECURITY.md §3.3): sensitive operations require that the session user
 * proved their password within the last five minutes (at login or via {@code POST /me/reauthenticate}).
 * API tokens cannot step up and are refused.
 */
public final class ReauthenticationGuard {

    public static final Duration WINDOW = Duration.ofMinutes(5);

    private ReauthenticationGuard() {}

    public static void require(AuthenticatedActor actor, Instant now) {
        if (actor.type() != ActorType.USER || actor.lastCredentialCheck().isBefore(now.minus(WINDOW))) {
            throw new ApiException(
                    PlatformErrorCode.REAUTHENTICATION_REQUIRED,
                    "Confirm your password (POST /api/v1/me/reauthenticate) and retry within 5 minutes.");
        }
    }
}
