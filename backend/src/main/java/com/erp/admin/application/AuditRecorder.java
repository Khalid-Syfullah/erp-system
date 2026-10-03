package com.erp.admin.application;

import com.erp.admin.persistence.AuditLogRepository;
import com.erp.platform.audit.AuditChange;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.logging.LogRedactor;
import com.erp.platform.security.ActorType;
import com.erp.platform.security.AuthenticatedActor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes audit events to {@code admin.audit_log} (SECURITY.md §8). Actor, request and client data
 * come from the {@link RequestContext}; without an authenticated actor the event is attributed to
 * {@code SYSTEM}.
 */
@Service
public class AuditRecorder implements AuditPort {

    private final AuditLogRepository repository;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate independentTransaction;

    public AuditRecorder(
            AuditLogRepository repository, JsonMapper jsonMapper, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        repository.insert(toEntry(event));
    }

    @Override
    public void recordIndependently(AuditEvent event) {
        AuditLogRepository.NewEntry entry = toEntry(event);
        independentTransaction.executeWithoutResult(status -> repository.insert(entry));
    }

    private AuditLogRepository.NewEntry toEntry(AuditEvent event) {
        RequestContext context = CurrentContext.get().orElse(null);
        AuthenticatedActor actor = context == null ? null : context.actor();
        ActorType actorType =
                actor != null ? actor.type() : event.actingUserId() != null ? ActorType.USER : ActorType.SYSTEM;
        return new AuditLogRepository.NewEntry(
                event.companyId() != null ? event.companyId() : context == null ? null : context.companyId(),
                actor != null ? actor.userId() : event.actingUserId(),
                actorType.name(),
                actor != null && actor.type() == ActorType.API_TOKEN ? actor.credentialId() : null,
                event.action(),
                event.module(),
                event.entityType(),
                event.entityId(),
                event.entityLabel(),
                event.fromState(),
                event.toState(),
                changesJson(event.changes()),
                context == null ? null : context.requestId(),
                context == null ? null : context.clientIp(),
                context == null ? null : context.userAgent());
    }

    private String changesJson(Map<String, AuditChange> changes) {
        if (changes.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> json = new LinkedHashMap<>();
        changes.forEach((field, change) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("old", truncate(change.oldValue()));
            values.put("new", truncate(change.newValue()));
            json.put(field, values);
        });
        return jsonMapper.writeValueAsString(json);
    }

    /** Large values are cut to 1 KB; strings pass through the log redactor as a safety net. */
    private static Object truncate(Object value) {
        if (value instanceof CharSequence text) {
            String redacted = LogRedactor.redact(text.toString());
            return redacted.length() > 1024 ? redacted.substring(0, 1024) + "…" : redacted;
        }
        return value == null
                ? null
                : value instanceof Number || value instanceof Boolean ? value : truncate(value.toString());
    }
}
