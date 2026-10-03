package com.erp.procurement.application;

import com.erp.org.api.OrganizationUsage;
import com.erp.org.api.TaxCodeUsage;
import com.erp.procurement.persistence.BillRepository;
import com.erp.procurement.persistence.PurchaseOrderRepository;
import com.erp.procurement.persistence.ReceiptRepository;
import com.erp.procurement.persistence.RequisitionRepository;
import com.erp.procurement.persistence.ReturnRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Procurement's answers to Org's usage ports (ADR-034): open requisitions, orders and draft
 * receipts and returns keep their branch (and department) in use; tax codes on submitted orders or
 * posted bills are frozen.
 */
@Component
class ProcurementUsage implements OrganizationUsage, TaxCodeUsage {

    private final RequisitionRepository requisitions;
    private final PurchaseOrderRepository orders;
    private final ReceiptRepository receipts;
    private final ReturnRepository returns;
    private final BillRepository bills;

    ProcurementUsage(
            RequisitionRepository requisitions,
            PurchaseOrderRepository orders,
            ReceiptRepository receipts,
            ReturnRepository returns,
            BillRepository bills) {
        this.requisitions = requisitions;
        this.orders = orders;
        this.receipts = receipts;
        this.returns = returns;
        this.bills = bills;
    }

    @Override
    public List<String> branchUsage(UUID companyId, UUID branchId) {
        List<String> uses = new ArrayList<>();
        if (requisitions.usesBranch(companyId, branchId)) {
            uses.add("open purchase requisitions");
        }
        if (orders.usesBranch(companyId, branchId)) {
            uses.add("open purchase orders");
        }
        if (receipts.usesBranch(companyId, branchId)) {
            uses.add("draft goods receipts");
        }
        if (returns.usesBranch(companyId, branchId)) {
            uses.add("draft purchase returns");
        }
        return uses;
    }

    @Override
    public List<String> departmentUsage(UUID companyId, UUID departmentId) {
        List<String> uses = new ArrayList<>();
        if (requisitions.usesDepartment(companyId, departmentId)) {
            uses.add("open purchase requisitions");
        }
        if (orders.usesDepartment(companyId, departmentId)) {
            uses.add("open purchase orders");
        }
        return uses;
    }

    @Override
    public boolean isUsed(UUID companyId, UUID taxCodeId) {
        return orders.usesTaxCode(companyId, taxCodeId) || bills.usesTaxCode(companyId, taxCodeId);
    }
}
