package com.erp.platform.context;

import java.util.Optional;
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
