package com.erp.platform.security;

import java.io.Serial;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Spring Security {@code Authentication} carrying an {@link AuthenticatedActor}. Authorities are
 * empty on purpose: permissions are company-scoped and resolved per request by {@link PermissionCheck}.
 */
public final class ActorAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final AuthenticatedActor actor;

    public ActorAuthentication(AuthenticatedActor actor) {
        super(List.of());
        this.actor = actor;
        setAuthenticated(true);
    }

    public AuthenticatedActor actor() {
        return actor;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return actor;
    }

    @Override
    public String getName() {
        return actor.userId().toString();
    }
}
