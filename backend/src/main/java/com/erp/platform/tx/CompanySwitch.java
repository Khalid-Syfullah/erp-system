package com.erp.platform.tx;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.jooq.TransactionSettings;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs work for another company inside the current transaction (DATABASE.md §3): sets the
 * transaction-local {@code app.company_id} and the {@link CurrentContext} company for the duration
 * of the work, then restores both. Used where one business transaction legitimately spans two
 * companies' rows, such as seeding a company's accounting in the transaction that creates it
 * (ADR-038). RLS keeps applying: the work sees and writes only the target company's rows.
 */
@Component
public class CompanySwitch {

    static final String COMPANY = "app.company_id";

    private final TransactionSettings settings;

    public CompanySwitch(TransactionSettings settings) {
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T inCompany(UUID companyId, Supplier<T> work) {
        String previous = settings.get(COMPANY);
        RequestContext context =
                CurrentContext.get().orElseGet(() -> RequestContext.forRequest("company-switch-" + UUID.randomUUID()));
        settings.set(COMPANY, companyId.toString());
        try {
            return CurrentContext.callWith(context.withCompany(companyId), work);
        } finally {
            settings.set(COMPANY, previous == null ? "" : previous);
        }
    }
}
