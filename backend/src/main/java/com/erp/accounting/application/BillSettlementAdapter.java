package com.erp.accounting.application;

import com.erp.accounting.persistence.OpenItemRepository;
import com.erp.procurement.api.BillSettlementPort;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link BillSettlementPort}: how much of a bill or debit note is still open (AP open item). */
@Component
class BillSettlementAdapter implements BillSettlementPort {

    private final OpenItemRepository openItems;

    BillSettlementAdapter(OpenItemRepository openItems) {
        this.openItems = openItems;
    }

    @Override
    @Transactional(readOnly = true)
    public Settlement settlement(UUID companyId, UUID billId) {
        return openItems
                .bySource(companyId, "procurement", "BILL", billId)
                .or(() -> openItems.bySource(companyId, "procurement", "DEBIT_NOTE", billId))
                .map(i -> new Settlement(
                        billId, Status.valueOf(i.status()), i.openAmount().abs()))
                .orElseGet(() -> new Settlement(billId, Status.UNKNOWN, null));
    }
}
