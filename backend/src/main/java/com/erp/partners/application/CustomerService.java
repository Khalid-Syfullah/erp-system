package com.erp.partners.application;

import com.erp.org.api.OrgFacade;
import com.erp.partners.persistence.CustomerRepository;
import com.erp.partners.persistence.GroupRepository;
import com.erp.partners.persistence.PartnerRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer profiles (PRODUCT_SPEC.md §5): group, currency, payment terms, default tax code, credit
 * limit (base currency) and the on-hold flag. A partner becomes a customer with its first profile.
 * {@code PUT} creates the profile ({@code If-Match: W/"0"}) or replaces it.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final PartnerRepository partners;
    private final GroupRepository groups;
    private final OrgFacade org;
    private final AuditPort audit;

    CustomerService(
            CustomerRepository customers,
            PartnerRepository partners,
            GroupRepository groups,
            OrgFacade org,
            AuditPort audit) {
        this.customers = customers;
        this.partners = partners;
        this.groups = groups;
        this.org = org;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PartnerViews.CustomerListItem> list(ListQuery query) {
        return customers.list(CurrentContext.requireCompany(), query);
    }

    @Transactional
    public PartnerViews.Customer replace(UUID partnerId, @Nullable String ifMatch, PartnerCommands.Customer command) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner =
                partners.lockForChange(companyId, partnerId).orElseThrow(ApiException::notFound);
        PartnerViews.Customer current = customers.find(companyId, partnerId).orElse(null);
        int version = current == null ? 0 : current.version();
        EntityTags.requireMatch(ifMatch, version);
        validate(companyId, command);
        if (!customers.save(
                companyId,
                partnerId,
                version,
                command,
                CurrentContext.requireActor().userId())) {
            throw new ApiException(
                    PlatformErrorCode.VERSION_CONFLICT, "The customer profile was modified concurrently.");
        }
        PartnerViews.Customer after = customers.find(companyId, partnerId).orElseThrow();
        audit.record(AuditEvent.builder(current == null ? "CREATE" : "UPDATE", "partners")
                .entity("customer", partnerId, partner.code())
                .change("customerGroupId", current == null ? null : current.customerGroupId(), after.customerGroupId())
                .change("currencyCode", current == null ? null : current.currencyCode(), after.currencyCode())
                .change("paymentTermsId", current == null ? null : current.paymentTermsId(), after.paymentTermsId())
                .change(
                        "defaultTaxCodeId",
                        current == null ? null : current.defaultTaxCodeId(),
                        after.defaultTaxCodeId())
                .change(
                        "creditLimit",
                        current == null ? null : Objects.toString(current.creditLimit(), null),
                        Objects.toString(after.creditLimit(), null))
                .change("onHold", current == null ? null : current.onHold(), after.onHold())
                .build());
        return after;
    }

    private void validate(UUID companyId, PartnerCommands.Customer command) {
        List<FieldViolation> violations = new ArrayList<>();
        if (!org.currency(command.currencyCode()).map(c -> c.active()).orElse(false)) {
            violations.add(FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency"));
        }
        if (command.paymentTermsId() != null
                && !org.paymentTerms(companyId, command.paymentTermsId())
                        .map(t -> t.active())
                        .orElse(false)) {
            violations.add(FieldViolation.atPointer(
                    "/paymentTermsId", "INVALID_VALUE", "must be active payment terms of the company"));
        }
        if (command.defaultTaxCodeId() != null
                && !org.taxCode(companyId, command.defaultTaxCodeId())
                        .map(t -> t.active() && t.appliesToSales())
                        .orElse(false)) {
            violations.add(FieldViolation.atPointer(
                    "/defaultTaxCodeId", "INVALID_VALUE", "must be an active sales tax code of the company"));
        }
        if (command.customerGroupId() != null
                && !groups.findForUse(companyId, command.customerGroupId())
                        .map(g -> g.isActive() && "CUSTOMER".equals(g.appliesTo()))
                        .orElse(false)) {
            violations.add(FieldViolation.atPointer(
                    "/customerGroupId", "INVALID_VALUE", "must be an active customer group of the company"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The customer profile is invalid.", violations);
        }
    }
}
