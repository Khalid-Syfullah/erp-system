package com.erp.accounting.application;

import com.erp.org.api.OrgFacade;
import com.erp.org.events.CompanyCreated;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.tx.CompanySwitch;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds a company's accounting (ADR-038): synchronously when {@code org.company.created} is
 * published, in the transaction that creates the company, and — for companies created before Phase
 * 8 — once at application start, each in its own transaction.
 */
@Component
class CompanySetupListener {

    private static final Logger log = LoggerFactory.getLogger(CompanySetupListener.class);

    private final AccountingSetup setup;
    private final CompanySwitch companySwitch;
    private final OrgFacade org;
    private final TransactionTemplate tx;

    CompanySetupListener(AccountingSetup setup, CompanySwitch companySwitch, OrgFacade org, TransactionTemplate tx) {
        this.setup = setup;
        this.companySwitch = companySwitch;
        this.org = org;
        this.tx = tx;
    }

    @EventListener
    void on(CompanyCreated event) {
        companySwitch.inCompany(event.companyId(), setup::seed);
    }

    @EventListener(ApplicationReadyEvent.class)
    void backfill() {
        for (UUID companyId : org.allCompanyIds()) {
            Boolean seeded = CurrentContext.callWith(
                    RequestContext.forRequest("accounting-backfill-" + UUID.randomUUID())
                            .withCompany(companyId),
                    () -> tx.execute(status -> setup.seed()));
            if (Boolean.TRUE.equals(seeded)) {
                log.info("Accounting set up for existing company {}", companyId);
            }
        }
    }
}
