package com.erp.platform.events;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A published domain event (ARCHITECTURE.md §7): an immutable fact in the past tense with the common
 * metadata. Events are Java records in {@code <module>.events}, published with Spring's
 * {@code ApplicationEventPublisher} inside the business transaction; synchronous listeners run in
 * that transaction.
 */
public interface DomainEvent {

    EventMetadata metadata();

    /** Common event fields. */
    record EventMetadata(
            UUID eventId,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            UUID companyId,
            @Nullable UUID actorUserId,
            @Nullable String correlationId) {}
}
