package com.erp.sales.application;

import com.erp.org.api.OrgFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.sales.domain.QuotationStatus;
import com.erp.sales.persistence.QuotationRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily quotation expiry (PRODUCT_SPEC.md §9.2): sent quotations whose {@code valid_until} lies
 * before the company's today become EXPIRED, in batches, each in its own transaction.
 */
@Component
class QuotationExpiry {

    static final int BATCH = 200;

    private final QuotationRepository quotations;
    private final OrgFacade org;
    private final AuditPort audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    QuotationExpiry(
            QuotationRepository quotations, OrgFacade org, AuditPort audit, TransactionTemplate tx, Clock clock) {
        this.quotations = quotations;
        this.org = org;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /** Expires the company's overdue quotations; returns how many. */
    int run(UUID companyId) {
        return CurrentContext.callWith(
                RequestContext.forRequest("job-sales-quotation-expiry-" + UUID.randomUUID())
                        .withCompany(companyId),
                () -> {
                    var profile = org.companyProfile(companyId).orElse(null);
                    if (profile == null) {
                        return 0;
                    }
                    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(profile.timezone())));
                    int total = 0;
                    while (true) {
                        Integer done = tx.execute(status -> {
                            var batch = quotations.expired(companyId, today, BATCH);
                            for (SalesViews.Quotation q : batch) {
                                String to = QuotationStatus.valueOf(q.status())
                                        .apply(QuotationStatus.Action.EXPIRE)
                                        .name();
                                quotations.transition(companyId, q.id(), q.version(), null, to, null, null, null);
                                audit.record(AuditEvent.builder("STATE_CHANGE", "sales")
                                        .entity("quotation", q.id(), q.number())
                                        .transition(q.status(), to)
                                        .detail("validUntil", q.validUntil())
                                        .build());
                            }
                            return batch.size();
                        });
                        total += done == null ? 0 : done;
                        if (done == null || done < BATCH) {
                            return total;
                        }
                    }
                });
    }
}
