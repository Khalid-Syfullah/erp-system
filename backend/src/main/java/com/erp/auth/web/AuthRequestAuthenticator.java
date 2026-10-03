package com.erp.auth.web;

import com.erp.auth.application.ApiTokenService;
import com.erp.auth.application.SessionService;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.platform.security.RequestAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Auth's implementation of the platform {@link RequestAuthenticator} port. */
@Component
class AuthRequestAuthenticator implements RequestAuthenticator {

    private final SessionService sessions;
    private final ApiTokenService apiTokens;
    private final SessionCookies cookies;

    AuthRequestAuthenticator(SessionService sessions, ApiTokenService apiTokens, SessionCookies cookies) {
        this.sessions = sessions;
        this.apiTokens = apiTokens;
        this.cookies = cookies;
    }

    @Override
    public Optional<AuthenticatedActor> authenticateBearer(String token, HttpServletRequest request) {
        return apiTokens.authenticate(token);
    }

    @Override
    public Optional<AuthenticatedActor> authenticateSession(HttpServletRequest request, HttpServletResponse response) {
        String token = cookies.readSession(request);
        if (token == null) {
            return Optional.empty();
        }
        Optional<AuthenticatedActor> actor = sessions.authenticate(token);
        if (actor.isEmpty()) {
            cookies.clearSession(response);
        }
        return actor;
    }
}
