package com.erp.partners.application;

import com.erp.partners.api.PartnersFacade;
import com.erp.partners.persistence.CustomerRepository;
import com.erp.partners.persistence.GroupRepository;
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
    private final CustomerRepository customers;
    private final PartnerRepository partners;
    private final GroupRepository groups;

    PartnersDirectory(
            SupplierRepository suppliers,
            CustomerRepository customers,
            PartnerRepository partners,
            GroupRepository groups) {
        this.suppliers = suppliers;
        this.customers = customers;
        this.partners = partners;
        this.groups = groups;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GroupInfo> group(UUID groupId) {
        return groups.find(CurrentContext.requireCompany(), groupId)
                .map(g -> new GroupInfo(g.id(), g.code(), g.appliesTo(), g.isActive()));
    }

    @Override
    @Transactional
    public boolean customerGroupUsable(UUID groupId) {
        return groups.findForUse(CurrentContext.requireCompany(), groupId)
                .filter(g -> g.isActive() && "CUSTOMER".equals(g.appliesTo()))
                .isPresent();
    }

    @Override
    @Transactional
    public Optional<CustomerInfo> customerForUse(UUID customerId) {
        return customers
                .findWithPartner(CurrentContext.requireCompany(), customerId, true)
                .map(PartnersDirectory::toCustomerInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerInfo> customer(UUID customerId) {
        return customers
                .findWithPartner(CurrentContext.requireCompany(), customerId, false)
                .map(PartnersDirectory::toCustomerInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AddressInfo> defaultAddress(UUID partnerId, String addressType) {
        return partners.addresses(CurrentContext.requireCompany(), partnerId).stream()
                .filter(a -> a.isDefault() && a.addressType().equals(addressType))
                .findFirst()
                .map(a -> new AddressInfo(
                        a.addressType(), a.line1(), a.line2(), a.city(), a.region(), a.postalCode(), a.countryCode()));
    }

    private static CustomerInfo toCustomerInfo(PartnerViews.CustomerListItem item) {
        PartnerViews.Partner p = item.partner();
        PartnerViews.Customer c = item.customer();
        return new CustomerInfo(
                p.id(),
                p.code(),
                p.name(),
                p.legalName(),
                p.status(),
                p.taxRegistrationNo(),
                c.customerGroupId(),
                c.currencyCode(),
                c.paymentTermsId(),
                c.defaultTaxCodeId(),
                c.creditLimit(),
                c.onHold());
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
