package com.erp.accounting.application;

import com.erp.accounting.persistence.CompanyBankAccountRepository;
import com.erp.accounting.persistence.LedgerRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual bank reconciliation (PRODUCT_SPEC.md §8.8, Q-17 default): posted lines of a bank or cash
 * account are marked as reconciled against a statement reference; removing the mark un-reconciles
 * them. The marks live beside the journal lines, which are never modified.
 */
@Service
public class ReconciliationService {

    private final LedgerRepository ledger;
    private final CompanyBankAccountRepository bankAccounts;
    private final AccountingContext context;
    private final AuditPort audit;

    ReconciliationService(
            LedgerRepository ledger,
            CompanyBankAccountRepository bankAccounts,
            AccountingContext context,
            AuditPort audit) {
        this.ledger = ledger;
        this.bankAccounts = bankAccounts;
        this.context = context;
        this.audit = audit;
    }

    /** Marks the lines reconciled; lines marked already keep their first mark. Returns how many were marked. */
    @Transactional
    public int mark(List<UUID> lineIds, String statementReference, LocalDate statementDate) {
        UUID companyId = context.companyId();
        Map<UUID, UUID> lineAccounts = bankLines(companyId, lineIds);
        int marked = 0;
        for (UUID lineId : lineIds) {
            UUID bankAccount = bankAccounts
                    .byGlAccount(companyId, lineAccounts.get(lineId))
                    .orElseThrow()
                    .id();
            if (ledger.mark(
                    companyId, lineId, bankAccount, statementReference.strip(), statementDate, context.actor())) {
                marked++;
            }
        }
        audit.record(AuditEvent.builder("RECONCILE", "accounting")
                .entity("bank_reconciliation", null, statementReference.strip())
                .detail("statementDate", statementDate)
                .detail("lines", marked)
                .build());
        return marked;
    }

    /** Removes the marks of the lines. Returns how many were removed. */
    @Transactional
    public int unmark(List<UUID> lineIds) {
        UUID companyId = context.companyId();
        bankLines(companyId, lineIds);
        int removed = 0;
        for (UUID lineId : lineIds) {
            if (ledger.unmark(companyId, lineId)) {
                removed++;
            }
        }
        audit.record(AuditEvent.builder("UNRECONCILE", "accounting")
                .entity("bank_reconciliation", null, null)
                .detail("lines", removed)
                .build());
        return removed;
    }

    /** The lines' accounts; each must be a posted line on the GL account of a bank account. */
    private Map<UUID, UUID> bankLines(UUID companyId, List<UUID> lineIds) {
        Map<UUID, UUID> accounts = ledger.postedLineAccounts(companyId, lineIds);
        List<FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < lineIds.size(); i++) {
            UUID account = accounts.get(lineIds.get(i));
            if (account == null || bankAccounts.byGlAccount(companyId, account).isEmpty()) {
                violations.add(FieldViolation.atPointer(
                        "/journalLineIds/" + i, "INVALID_VALUE", "must be a posted line of a bank or cash account"));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The reconciliation is invalid.", violations);
        }
        return accounts;
    }
}
