package com.erp.partners.application;

import com.erp.org.api.OrgFacade;
import com.erp.partners.persistence.GroupRepository;
import com.erp.partners.persistence.PartnerRepository;
import com.erp.partners.persistence.SupplierRepository;
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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier profiles (PRODUCT_SPEC.md §5): group, currency, payment terms, default tax code and lead
 * time. A partner becomes a supplier with its first profile; the profile defaults new purchase
 * documents. {@code PUT} creates the profile ({@code If-Match: W/"0"}) or replaces it.
 */
@Service
public class SupplierService {

    private final SupplierRepository suppliers;
    private final PartnerRepository partners;
    private final GroupRepository groups;
    private final OrgFacade org;
    private final AuditPort audit;

    SupplierService(
            SupplierRepository suppliers,
            PartnerRepository partners,
            GroupRepository groups,
            OrgFacade org,
            AuditPort audit) {
        this.suppliers = suppliers;
        this.partners = partners;
        this.groups = groups;
        this.org = org;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PartnerViews.SupplierListItem> list(ListQuery query) {
        return suppliers.list(CurrentContext.requireCompany(), query);
    }

    @Transactional
    public PartnerViews.Supplier replace(UUID partnerId, @Nullable String ifMatch, PartnerCommands.Supplier command) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner =
                partners.lockForChange(companyId, partnerId).orElseThrow(ApiException::notFound);
        PartnerViews.Supplier current = suppliers.find(companyId, partnerId).orElse(null);
        int version = current == null ? 0 : current.version();
        EntityTags.requireMatch(ifMatch, version);
        validate(companyId, command);
        if (!suppliers.save(
                companyId,
                partnerId,
                version,
                command,
                CurrentContext.requireActor().userId())) {
            throw new ApiException(
                    PlatformErrorCode.VERSION_CONFLICT, "The supplier profile was modified concurrently.");
        }
        PartnerViews.Supplier after = suppliers.find(companyId, partnerId).orElseThrow();
        audit.record(AuditEvent.builder(current == null ? "CREATE" : "UPDATE", "partners")
                .entity("supplier", partnerId, partner.code())
                .change("supplierGroupId", current == null ? null : current.supplierGroupId(), after.supplierGroupId())
                .change("currencyCode", current == null ? null : current.currencyCode(), after.currencyCode())
                .change("paymentTermsId", current == null ? null : current.paymentTermsId(), after.paymentTermsId())
                .change(
                        "defaultTaxCodeId",
                        current == null ? null : current.defaultTaxCodeId(),
                        after.defaultTaxCodeId())
                .change("leadTimeDays", current == null ? null : current.leadTimeDays(), after.leadTimeDays())
                .build());
        return after;
    }

    private void validate(UUID companyId, PartnerCommands.Supplier command) {
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
                        .map(t -> t.active() && t.appliesToPurchases())
                        .orElse(false)) {
            violations.add(FieldViolation.atPointer(
                    "/defaultTaxCodeId", "INVALID_VALUE", "must be an active purchase tax code of the company"));
        }
        if (command.supplierGroupId() != null
                && !groups.findForUse(companyId, command.supplierGroupId())
                        .map(g -> g.isActive() && "SUPPLIER".equals(g.appliesTo()))
                        .orElse(false)) {
            violations.add(FieldViolation.atPointer(
                    "/supplierGroupId", "INVALID_VALUE", "must be an active supplier group of the company"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The supplier profile is invalid.", violations);
        }
    }
}
