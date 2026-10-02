package com.erp.platform.context;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who is acting, in which company, for which request (ARCHITECTURE.md §6.1, §6.3). Immutable;
 * enriched step by step (request ID, then authenticated user in Phase 3, then active company).
 *
 * @param requestId correlation ID, also returned as {@code X-Request-Id} and in problem responses
 * @param userId authenticated user, if any
 * @param companyId active company; drives the {@code app.company_id} RLS setting of transactions
 */
public record RequestContext(
        String requestId, @Nullable UUID userId, @Nullable UUID companyId) {

    public RequestContext {
        Objects.requireNonNull(requestId, "requestId");
    }

    public static RequestContext forRequest(String requestId) {
        return new RequestContext(requestId, null, null);
    }

    public RequestContext withUser(@Nullable UUID user) {
        return new RequestContext(requestId, user, companyId);
    }

    public RequestContext withCompany(@Nullable UUID company) {
        return new RequestContext(requestId, userId, company);
    }
}
