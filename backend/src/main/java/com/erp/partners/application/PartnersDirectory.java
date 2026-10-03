package com.erp.partners.application;

import com.erp.partners.api.PartnersFacade;
import com.erp.partners.persistence.PartnerRepository;
import com.erp.partners.persistence.SupplierRepository;
import com.erp.platform.context.CurrentContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PartnersFacade} implementation; runs in the caller's company transaction. */
@Service
class PartnersDirectory implements PartnersFacade {

    private final SupplierRepository suppliers;
    private final PartnerRepository partners;

    PartnersDirectory(SupplierRepository suppliers, PartnerRepository partners) {
        this.suppliers = suppliers;
        this.partners = partners;
    }

    @Override
    @Transactional
    public Optional<SupplierInfo> supplierForUse(UUID supplierId) {
        return suppliers
                .findWithPartner(CurrentContext.requireCompany(), supplierId, true)
                .map(PartnersDirectory::toInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupplierInfo> supplier(UUID supplierId) {
        return suppliers
                .findWithPartner(CurrentContext.requireCompany(), supplierId, false)
                .map(PartnersDirectory::toInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PartnerSummary> partners(Collection<UUID> partnerIds) {
        Map<UUID, PartnerSummary> result = new LinkedHashMap<>();
        if (partnerIds.isEmpty()) {
            return result;
        }
        partners.findAll(CurrentContext.requireCompany(), partnerIds)
                .forEach(p -> result.put(p.id(), new PartnerSummary(p.id(), p.code(), p.name(), p.status())));
        return result;
    }

    private static SupplierInfo toInfo(PartnerViews.SupplierListItem item) {
        PartnerViews.Partner p = item.partner();
        PartnerViews.Supplier s = item.supplier();
        return new SupplierInfo(
                p.id(),
                p.code(),
                p.name(),
                p.legalName(),
                p.status(),
                p.taxRegistrationNo(),
                s.supplierGroupId(),
                s.currencyCode(),
                s.paymentTermsId(),
                s.defaultTaxCodeId(),
                s.leadTimeDays());
    }
}
