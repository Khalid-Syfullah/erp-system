package com.erp.sales.application;

import com.erp.org.api.OrganizationUsage;
import com.erp.org.api.TaxCodeUsage;
import com.erp.sales.persistence.DeliveryRepository;
import com.erp.sales.persistence.InvoiceRepository;
import com.erp.sales.persistence.QuotationRepository;
import com.erp.sales.persistence.SalesOrderRepository;
import com.erp.sales.persistence.SalesReturnRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sales' answers to Org's usage ports (ADR-034): open quotations and orders, draft deliveries,
 * returns and invoices keep their branch (and department) in use; tax codes on sent quotations,
 * confirmed orders or posted invoices are frozen.
 */
@Component
class SalesUsage implements OrganizationUsage, TaxCodeUsage {

    private final QuotationRepository quotations;
    private final SalesOrderRepository orders;
    private final DeliveryRepository deliveries;
    private final SalesReturnRepository returns;
    private final InvoiceRepository invoices;

    SalesUsage(
            QuotationRepository quotations,
            SalesOrderRepository orders,
            DeliveryRepository deliveries,
            SalesReturnRepository returns,
            InvoiceRepository invoices) {
        this.quotations = quotations;
        this.orders = orders;
        this.deliveries = deliveries;
        this.returns = returns;
        this.invoices = invoices;
    }

    @Override
    public List<String> branchUsage(UUID companyId, UUID branchId) {
        List<String> uses = new ArrayList<>();
        if (quotations.usesBranch(companyId, branchId)) {
            uses.add("open quotations");
        }
        if (orders.usesBranch(companyId, branchId)) {
            uses.add("open sales orders");
        }
        if (deliveries.usesBranch(companyId, branchId)) {
            uses.add("draft deliveries");
        }
        if (returns.usesBranch(companyId, branchId)) {
            uses.add("draft sales returns");
        }
        if (invoices.usesBranch(companyId, branchId)) {
            uses.add("draft invoices");
        }
        return uses;
    }

    @Override
    public List<String> departmentUsage(UUID companyId, UUID departmentId) {
        return invoices.usesDepartment(companyId, departmentId) ? List.of("draft invoices") : List.of();
    }

    @Override
    public boolean isUsed(UUID companyId, UUID taxCodeId) {
        return quotations.usesTaxCode(companyId, taxCodeId)
                || orders.usesTaxCode(companyId, taxCodeId)
                || invoices.usesTaxCode(companyId, taxCodeId);
    }
}
