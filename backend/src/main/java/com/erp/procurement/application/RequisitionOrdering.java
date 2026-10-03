package com.erp.procurement.application;

import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.procurement.domain.RequisitionStatus;
import com.erp.procurement.persistence.RequisitionRepository;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps requisitions in step with the purchase orders made from them: a requisition line's ordered
 * quantity is the sum of its lines on live (not cancelled) orders, never counted separately, and
 * the requisition moves between APPROVED, PARTIALLY_ORDERED and ORDERED accordingly. Callers hold
 * the purchase order lock; the requisitions are locked here (lock order: order → requisition).
 */
@Component
class RequisitionOrdering {

    private final RequisitionRepository requisitions;
    private final ProcurementContext context;
    private final AuditPort audit;

    RequisitionOrdering(RequisitionRepository requisitions, ProcurementContext context, AuditPort audit) {
        this.requisitions = requisitions;
        this.context = context;
        this.audit = audit;
    }

    /** Recomputes the requisitions owning these lines; over-ordering a line is refused (422). */
    void refresh(UUID companyId, Collection<UUID> requisitionLineIds) {
        for (ProcurementViews.Requisition requisition :
                requisitions.lockOwnersOfLines(companyId, requisitionLineIds).values()) {
            refresh(requisition);
        }
    }

    void refresh(ProcurementViews.Requisition requisition) {
        UUID companyId = requisition.companyId();
        Map<UUID, BigDecimal> ordered = requisitions.orderedQuantities(companyId, requisition.id());
        boolean any = false;
        boolean all = true;
        for (ProcurementViews.RequisitionLine line : requisitions.lines(companyId, requisition.id())) {
            BigDecimal quantity = ordered.getOrDefault(line.id(), BigDecimal.ZERO);
            if (quantity.compareTo(line.quantityBase()) > 0) {
                throw new ApiException(
                        ProcurementErrorCode.QUANTITY_EXCEEDS_REMAINING,
                        "Requisition " + requisition.number() + " line " + line.lineNo() + " would be ordered "
                                + quantity.stripTrailingZeros().toPlainString() + " of "
                                + line.quantityBase().stripTrailingZeros().toPlainString() + ".");
            }
            if (quantity.compareTo(line.orderedQuantityBase()) != 0) {
                requisitions.setOrderedQuantity(companyId, line.id(), quantity);
            }
            any |= quantity.signum() > 0;
            all &= quantity.compareTo(line.quantityBase()) >= 0;
        }
        RequisitionStatus from = RequisitionStatus.valueOf(requisition.status());
        if (!from.allows(RequisitionStatus.Action.ORDER)) {
            return;
        }
        RequisitionStatus to = from.ordered(any, all);
        if (to != from) {
            if (!requisitions.transition(
                    companyId,
                    requisition.id(),
                    requisition.version(),
                    context.actor(),
                    to.name(),
                    null,
                    false,
                    false,
                    null,
                    null)) {
                throw new IllegalStateException("Requisition changed although it is locked");
            }
            audit.record(AuditEvent.builder("STATE_CHANGE", "procurement")
                    .entity("purchase_requisition", requisition.id(), requisition.number())
                    .transition(from.name(), to.name())
                    .build());
        }
    }
}
