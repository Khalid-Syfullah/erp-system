package com.erp.platform.context;

import com.erp.platform.security.AuthenticatedActor;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who is acting, in which company, for which request (ARCHITECTURE.md §6.1, §6.3). Immutable;
 * enriched step by step: request ID and client details (request filter), the authenticated actor
 * (security filter), then the active company and branch scope (company context interceptor).
 *
 * @param requestId correlation ID, also returned as {@code X-Request-Id} and in problem responses
 * @param clientIp client address (after trusted proxy headers), for audit records
 * @param userAgent client user agent (truncated), for audit records
 * @param actor authenticated principal, if any
 * @param companyId active company; drives the {@code app.company_id} RLS setting of transactions
 * @param branchScope branches visible in the active company; {@code null} means all
 * @param globalAccess system-administration request allowed to cross companies where RLS permits
 */
public record RequestContext(
        String requestId,
        @Nullable String clientIp,
        @Nullable String userAgent,
        @Nullable AuthenticatedActor actor,
        @Nullable UUID companyId,
        @Nullable Set<UUID> branchScope,
        boolean globalAccess) {

    public RequestContext {
        Objects.requireNonNull(requestId, "requestId");
        branchScope = branchScope == null ? null : Set.copyOf(branchScope);
    }

    public static RequestContext forRequest(String requestId) {
        return new RequestContext(requestId, null, null, null, null, null, false);
    }

    public static RequestContext forRequest(String requestId, @Nullable String clientIp, @Nullable String userAgent) {
        return new RequestContext(requestId, clientIp, userAgent, null, null, null, false);
    }

    /** The acting user, if authenticated. */
    public @Nullable UUID userId() {
        return actor == null ? null : actor.userId();
    }

    public RequestContext withActor(@Nullable AuthenticatedActor newActor) {
        return new RequestContext(requestId, clientIp, userAgent, newActor, companyId, branchScope, globalAccess);
    }

    /** Active company with access to all of its branches. */
    public RequestContext withCompany(@Nullable UUID company) {
        return new RequestContext(requestId, clientIp, userAgent, actor, company, null, globalAccess);
    }

    public RequestContext withCompany(UUID company, @Nullable Set<UUID> branches) {
        return new RequestContext(requestId, clientIp, userAgent, actor, company, branches, globalAccess);
    }

    public RequestContext withGlobalAccess() {
        return new RequestContext(requestId, clientIp, userAgent, actor, companyId, branchScope, true);
    }

    /** Whether the branch is inside the branch scope (always true when the scope is unrestricted). */
    public boolean canSeeBranch(UUID branchId) {
        return branchScope == null || branchScope.contains(branchId);
    }
}
