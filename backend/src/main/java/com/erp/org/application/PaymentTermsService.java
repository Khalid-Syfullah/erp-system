package com.erp.org.application;

import com.erp.org.domain.DueDateBasis;
import com.erp.org.persistence.PaymentTermsRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Payment terms of the active company. Documents copy the computed due date, so the terms themselves
 * may be edited; unused-for-new-documents terms are deactivated rather than deleted.
 */
@Service
public class PaymentTermsService {

    public static final int MAX_DUE_DAYS = 3650;
    static final Set<String> PATCHABLE = Set.of("name", "dueDays", "dueBasis");

    private final PaymentTermsRepository terms;
    private final AuditPort audit;

    public PaymentTermsService(PaymentTermsRepository terms, AuditPort audit) {
        this.terms = terms;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentTermsView> list(ListQuery query) {
        return terms.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public PaymentTermsView get(UUID id) {
        return terms.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    /** The due date of a document dated {@code documentDate} under these terms. */
    @Transactional(readOnly = true)
    public LocalDate dueDate(UUID id, LocalDate documentDate) {
        PaymentTermsView view = get(id);
        return DueDateBasis.valueOf(view.dueBasis()).dueDate(documentDate, view.dueDays());
    }

    @Transactional
    public PaymentTermsView create(ReferenceCommands.PaymentTerms command) {
        UUID id = terms.insert(
                CurrentContext.requireCompany(),
                command,
                CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "org")
                .entity("payment_terms", id, command.code())
                .detail("dueDays", command.dueDays())
                .detail("dueBasis", command.dueBasis())
                .build());
        return get(id);
    }

    @Transactional
    public PaymentTermsView patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        PaymentTermsView current = terms.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 100);
        var dueDays = patch.integer("dueDays", 0, MAX_DUE_DAYS);
        var dueBasis = patch.text(
                "dueBasis",
                true,
                20,
                b -> Set.of("DOCUMENT_DATE", "END_OF_MONTH").contains(b)
                        ? null
                        : "must be DOCUMENT_DATE or END_OF_MONTH");
        patch.throwIfInvalid();
        update(
                current,
                new ReferenceCommands.PaymentTerms(
                        current.code(),
                        name.orElse(current.name()),
                        dueDays.orElse(current.dueDays()),
                        dueBasis.orElse(current.dueBasis()),
                        current.active()));
        PaymentTermsView after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "org")
                .entity("payment_terms", id, after.code())
                .change("name", current.name(), after.name())
                .change("dueDays", current.dueDays(), after.dueDays())
                .change("dueBasis", current.dueBasis(), after.dueBasis())
                .build());
        return after;
    }

    @Transactional
    public PaymentTermsView setActive(UUID id, @Nullable String ifMatch, boolean active) {
        UUID companyId = CurrentContext.requireCompany();
        PaymentTermsView current = terms.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (current.active() == active) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "The payment terms are already " + (active ? "active." : "inactive."));
        }
        update(
                current,
                new ReferenceCommands.PaymentTerms(
                        current.code(), current.name(), current.dueDays(), current.dueBasis(), active));
        audit.record(AuditEvent.builder("STATE_CHANGE", "org")
                .entity("payment_terms", id, current.code())
                .transition(current.active() ? "ACTIVE" : "INACTIVE", active ? "ACTIVE" : "INACTIVE")
                .build());
        return get(id);
    }

    private void update(PaymentTermsView current, ReferenceCommands.PaymentTerms next) {
        if (!terms.update(
                current.companyId(),
                current.id(),
                current.version(),
                CurrentContext.requireActor().userId(),
                next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The payment terms were modified concurrently.");
        }
    }
}
