package com.erp.platform.events;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import java.time.Clock;
import java.util.UUID;

/** Builds event metadata from the current request context. */
public final class DomainEvents {

    private DomainEvents() {}

    public static DomainEvent.EventMetadata metadata(String eventType, int schemaVersion, UUID companyId, Clock clock) {
        RequestContext context = CurrentContext.get().orElse(null);
        return new DomainEvent.EventMetadata(
                newEventId(),
                eventType,
                schemaVersion,
                clock.instant(),
                companyId,
                context == null || context.actor() == null
                        ? null
                        : context.actor().userId(),
                context == null ? null : context.requestId());
    }

    /** A time-ordered (version 7) UUID. */
    static UUID newEventId() {
        long millis = System.currentTimeMillis();
        java.security.SecureRandom random = RANDOM;
        long mostSig = (millis << 16) | 0x7000L | (random.nextInt() & 0x0FFFL);
        long leastSig = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSig, leastSig);
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
}
