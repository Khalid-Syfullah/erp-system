package com.erp.platform.context;

import com.erp.platform.security.AuthenticatedActor;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Holder of the {@link RequestContext} for the current thread (one thread per request; virtual
 * threads are fine). Set by the request filter, cleared when the request completes. Jobs and tests
 * use {@link #callWith} so that the previous context is always restored.
 */
public final class CurrentContext {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private CurrentContext() {}

    public static Optional<RequestContext> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    /** The current context; fails if none is bound (programming error, not a client error). */
    public static RequestContext require() {
        return get().orElseThrow(() -> new IllegalStateException("No request context bound to this thread"));
    }

    /** The authenticated actor of the current request; fails if the request is anonymous. */
    public static AuthenticatedActor requireActor() {
        AuthenticatedActor actor = require().actor();
        if (actor == null) {
            throw new IllegalStateException("No authenticated actor in the request context");
        }
        return actor;
    }

    /** The active company of the current request; fails outside company-scoped requests. */
    public static UUID requireCompany() {
        UUID company = require().companyId();
        if (company == null) {
            throw new IllegalStateException("No active company in the request context");
        }
        return company;
    }

    public static void set(RequestContext context) {
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static <T> T callWith(RequestContext context, Supplier<T> action) {
        RequestContext previous = HOLDER.get();
        HOLDER.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    public static void runWith(RequestContext context, Runnable action) {
        callWith(context, () -> {
            action.run();
            return null;
        });
    }
}
