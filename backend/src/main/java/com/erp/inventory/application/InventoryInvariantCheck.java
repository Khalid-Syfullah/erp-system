package com.erp.inventory.application;

import com.erp.inventory.persistence.StockRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nightly inventory invariant checks (ARCHITECTURE.md §6.8): location balances equal the ledger,
 * warehouse stock equals its counting locations, valuations equal the ledger and reserved quantities
 * equal active reservations. Drift is logged as an error and audit-logged, for alerting.
 */
@Component
public class InventoryInvariantCheck {

    private static final Logger log = LoggerFactory.getLogger(InventoryInvariantCheck.class);

    /** Mismatch counts of one company. */
    public record Report(UUID companyId, int balances, int warehouses, int valuations, int reservations) {

        public boolean clean() {
            return balances == 0 && warehouses == 0 && valuations == 0 && reservations == 0;
        }
    }

    private final StockRepository stock;
    private final AuditPort audit;
    private final TransactionTemplate tx;

    InventoryInvariantCheck(StockRepository stock, AuditPort audit, TransactionTemplate tx) {
        this.stock = stock;
        this.audit = audit;
        this.tx = tx;
    }

    /** Checks one company in a read-write transaction (the audit record of a failure is written). */
    public Report check(UUID companyId) {
        return CurrentContext.callWith(
                RequestContext.forRequest("job-inventory-invariants-" + UUID.randomUUID())
                        .withCompany(companyId),
                () -> tx.execute(status -> {
                    Report report = new Report(
                            companyId,
                            stock.balanceLedgerMismatches(companyId),
                            stock.warehouseBalanceMismatches(companyId),
                            stock.valuationLedgerMismatches(companyId),
                            stock.reservationMismatches(companyId));
                    if (!report.clean()) {
                        log.error("Inventory invariant violation: {}", report);
                        audit.record(AuditEvent.builder("INVARIANT_VIOLATION", "inventory")
                                .entity("company", companyId, null)
                                .detail("balances", report.balances())
                                .detail("warehouses", report.warehouses())
                                .detail("valuations", report.valuations())
                                .detail("reservations", report.reservations())
                                .build());
                    }
                    return report;
                }));
    }
}
