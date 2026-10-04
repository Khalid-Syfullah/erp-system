package com.erp.accounting.application;

import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.LedgerRepository;
import com.erp.accounting.persistence.OpenItemRepository;
import com.erp.inventory.api.InventoryFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger invariants (PRODUCT_SPEC.md §8.3), checked nightly and by the tests: the trial balance
 * is level (ACC-1); the AR and AP control accounts equal Σ of their open items in base currency, and
 * the inventory accounts equal Σ item valuations (ACC-6). A violation is logged and audited.
 */
@Component
public class LedgerInvariantCheck {

    private static final Logger log = LoggerFactory.getLogger(LedgerInvariantCheck.class);

    /** Differences found (zero when the invariant holds), base currency. */
    public record Report(
            UUID companyId,
            BigDecimal trialBalance,
            BigDecimal receivables,
            BigDecimal payables,
            BigDecimal inventory) {

        public boolean clean() {
            return trialBalance.signum() == 0
                    && receivables.signum() == 0
                    && payables.signum() == 0
                    && inventory.signum() == 0;
        }
    }

    private final LedgerRepository ledger;
    private final AccountRepository accounts;
    private final OpenItemRepository openItems;
    private final InventoryFacade inventory;
    private final AuditPort audit;
    private final TransactionTemplate tx;

    LedgerInvariantCheck(
            LedgerRepository ledger,
            AccountRepository accounts,
            OpenItemRepository openItems,
            InventoryFacade inventory,
            AuditPort audit,
            TransactionTemplate tx) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.openItems = openItems;
        this.inventory = inventory;
        this.audit = audit;
        this.tx = tx;
    }

    /** Checks one company in its own transaction. */
    public Report check(UUID companyId) {
        return CurrentContext.callWith(
                RequestContext.forRequest("job-accounting-invariants-" + UUID.randomUUID())
                        .withCompany(companyId),
                () -> tx.execute(status -> {
                    BigDecimal[] totals = ledger.totals(companyId);
                    Map<UUID, BigDecimal> balances = ledger.balances(companyId);
                    BigDecimal receivableAccounts = BigDecimal.ZERO;
                    BigDecimal payableAccounts = BigDecimal.ZERO;
                    BigDecimal inventoryAccounts = BigDecimal.ZERO;
                    for (AccountingViews.Account account : accounts.all(companyId)) {
                        BigDecimal balance = balances.getOrDefault(account.id(), BigDecimal.ZERO);
                        switch (account.accountSubtype()) {
                            case "RECEIVABLE" -> receivableAccounts = receivableAccounts.add(balance);
                            case "PAYABLE" -> payableAccounts = payableAccounts.add(balance.negate());
                            case "INVENTORY" -> inventoryAccounts = inventoryAccounts.add(balance);
                            default -> {}
                        }
                    }
                    BigDecimal receivableItems = openItems.openBaseByAccount(companyId, "RECEIVABLE").values().stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal payableItems = openItems.openBaseByAccount(companyId, "PAYABLE").values().stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    Report report = new Report(
                            companyId,
                            totals[0].subtract(totals[1]),
                            receivableAccounts.subtract(receivableItems),
                            payableAccounts.subtract(payableItems),
                            inventoryAccounts.subtract(inventory.valuationTotalBase()));
                    if (!report.clean()) {
                        log.error("Accounting invariant violation: {}", report);
                        audit.record(AuditEvent.builder("INVARIANT_VIOLATION", "accounting")
                                .entity("company", companyId, null)
                                .detail("trialBalance", report.trialBalance().toPlainString())
                                .detail("receivables", report.receivables().toPlainString())
                                .detail("payables", report.payables().toPlainString())
                                .detail("inventory", report.inventory().toPlainString())
                                .build());
                    }
                    return report;
                }));
    }
}
