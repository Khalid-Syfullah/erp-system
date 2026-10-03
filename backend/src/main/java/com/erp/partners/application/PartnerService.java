package com.erp.partners.application;

import com.erp.org.api.OrgFacade;
import com.erp.partners.domain.PartnerStatus;
import com.erp.partners.persistence.CustomerRepository;
import com.erp.partners.persistence.PartnerRepository;
import com.erp.partners.persistence.SupplierRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Partners with their addresses and contacts (PRODUCT_SPEC.md §5). The code is fixed once created;
 * status changes go through {@link PartnerStatus}. Only ACTIVE partners are used on new documents.
 */
@Service
public class PartnerService {

    static final Set<String> PATCHABLE =
            Set.of("name", "legalName", "partnerType", "taxRegistrationNo", "email", "phone", "website", "notes");
    static final Set<String> ADDRESS_PATCHABLE =
            Set.of("addressType", "line1", "line2", "city", "region", "postalCode", "countryCode", "isDefault");
    static final Set<String> CONTACT_PATCHABLE = Set.of("name", "email", "phone", "roleTitle", "isPrimary");
    private static final String EMAIL = "^[^@\\s]+@[^@\\s]+$";

    private final PartnerRepository partners;
    private final SupplierRepository suppliers;
    private final CustomerRepository customers;
    private final OrgFacade org;
    private final AuditPort audit;

    PartnerService(
            PartnerRepository partners,
            SupplierRepository suppliers,
            CustomerRepository customers,
            OrgFacade org,
            AuditPort audit) {
        this.customers = customers;
        this.partners = partners;
        this.suppliers = suppliers;
        this.org = org;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PartnerViews.Partner> list(ListQuery query) {
        return partners.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public PartnerViews.PartnerDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner = partners.find(companyId, id).orElseThrow(ApiException::notFound);
        return new PartnerViews.PartnerDetail(
                partner,
                partners.addresses(companyId, id),
                partners.contacts(companyId, id),
                suppliers.find(companyId, id).orElse(null),
                customers.find(companyId, id).orElse(null));
    }

    @Transactional
    public PartnerViews.PartnerDetail create(PartnerCommands.Partner command) {
        UUID companyId = CurrentContext.requireCompany();
        UUID id = partners.insert(companyId, command, actor());
        audit.record(AuditEvent.builder("CREATE", "partners")
                .entity("partner", id, command.code())
                .detail("name", command.name())
                .detail("partnerType", command.partnerType())
                .detail("taxRegistrationNo", command.taxRegistrationNo())
                .build());
        return get(id);
    }

    @Transactional
    public PartnerViews.PartnerDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 200);
        var legalName = patch.text("legalName", false, 200);
        var type = patch.text(
                "partnerType",
                true,
                20,
                v -> Set.of("ORGANIZATION", "INDIVIDUAL").contains(v) ? null : "must be ORGANIZATION or INDIVIDUAL");
        var tax = patch.text("taxRegistrationNo", false, 50);
        var email = patch.text("email", false, 254, v -> v.matches(EMAIL) ? null : "must be an email address");
        var phone = patch.text("phone", false, 40);
        var website = patch.text("website", false, 200);
        var notes = patch.text("notes", false, 4000);
        patch.throwIfInvalid();
        PartnerCommands.Partner next = new PartnerCommands.Partner(
                current.code(),
                name.orElse(current.name()),
                legalName.orElse(current.legalName()),
                type.orElse(current.partnerType()),
                tax.orElse(current.taxRegistrationNo()),
                email.orElse(current.email()),
                phone.orElse(current.phone()),
                website.orElse(current.website()),
                notes.orElse(current.notes()));
        update(current, next, current.status());
        PartnerViews.Partner after = partners.find(companyId, id).orElseThrow();
        audit.record(AuditEvent.builder("UPDATE", "partners")
                .entity("partner", id, current.code())
                .change("name", current.name(), after.name())
                .change("legalName", current.legalName(), after.legalName())
                .change("partnerType", current.partnerType(), after.partnerType())
                .change("taxRegistrationNo", current.taxRegistrationNo(), after.taxRegistrationNo())
                .change("email", current.email(), after.email())
                .change("phone", current.phone(), after.phone())
                .change("website", current.website(), after.website())
                .change("notes", current.notes(), after.notes())
                .build());
        return get(id);
    }

    /** Activates, deactivates or blocks the partner; open documents can still be completed. */
    @Transactional
    public PartnerViews.PartnerDetail changeStatus(UUID id, @Nullable String ifMatch, PartnerStatus.Action action) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner current = lock(companyId, id);
        EntityTags.requireMatch(ifMatch, current.version());
        PartnerStatus from = PartnerStatus.valueOf(current.status());
        if (!from.allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + from + " partner does not allow " + action.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        PartnerStatus to = from.apply(action);
        update(current, asCommand(current), to.name());
        audit.record(AuditEvent.builder("STATE_CHANGE", "partners")
                .entity("partner", id, current.code())
                .transition(from.name(), to.name())
                .build());
        return get(id);
    }

    // ---------------------------------------------------------------------------- addresses

    @Transactional(readOnly = true)
    public List<PartnerViews.Address> addresses(UUID partnerId) {
        UUID companyId = CurrentContext.requireCompany();
        partners.find(companyId, partnerId).orElseThrow(ApiException::notFound);
        return partners.addresses(companyId, partnerId);
    }

    @Transactional
    public PartnerViews.Address addAddress(UUID partnerId, PartnerCommands.Address command) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner = lock(companyId, partnerId);
        checkCountry(command.countryCode());
        if (command.isDefault()) {
            partners.clearDefaultAddress(companyId, partnerId, command.addressType(), null);
        }
        UUID id = partners.insertAddress(companyId, partnerId, command, actor());
        audit.record(AuditEvent.builder("CREATE", "partners")
                .entity("partner_address", id, partner.code())
                .detail("addressType", command.addressType())
                .detail("countryCode", command.countryCode())
                .build());
        return address(companyId, partnerId, id);
    }

    @Transactional
    public PartnerViews.Address patchAddress(
            UUID partnerId, UUID addressId, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner = lock(companyId, partnerId);
        PartnerViews.Address current = address(companyId, partnerId, addressId);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, ADDRESS_PATCHABLE);
        var type = patch.text(
                "addressType",
                true,
                20,
                v -> Set.of("BILLING", "SHIPPING", "OTHER").contains(v) ? null : "must be BILLING, SHIPPING or OTHER");
        var line1 = patch.text("line1", true, 200);
        var line2 = patch.text("line2", false, 200);
        var city = patch.text("city", false, 100);
        var region = patch.text("region", false, 100);
        var postal = patch.text("postalCode", false, 20);
        var country =
                patch.text("countryCode", true, 2, v -> v.matches("^[A-Z]{2}$") ? null : "must be an ISO 3166 code");
        var isDefault = patch.bool("isDefault");
        patch.throwIfInvalid();
        PartnerCommands.Address next = new PartnerCommands.Address(
                type.orElse(current.addressType()),
                line1.orElse(current.line1()),
                line2.orElse(current.line2()),
                city.orElse(current.city()),
                region.orElse(current.region()),
                postal.orElse(current.postalCode()),
                country.orElse(current.countryCode()),
                Boolean.TRUE.equals(isDefault.orElse(current.isDefault())));
        checkCountry(next.countryCode());
        if (next.isDefault()) {
            partners.clearDefaultAddress(companyId, partnerId, next.addressType(), addressId);
        }
        if (!partners.updateAddress(companyId, partnerId, addressId, current.version(), actor(), next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The address was modified concurrently.");
        }
        PartnerViews.Address after = address(companyId, partnerId, addressId);
        audit.record(AuditEvent.builder("UPDATE", "partners")
                .entity("partner_address", addressId, partner.code())
                .change("addressType", current.addressType(), after.addressType())
                .change("line1", current.line1(), after.line1())
                .change("line2", current.line2(), after.line2())
                .change("city", current.city(), after.city())
                .change("region", current.region(), after.region())
                .change("postalCode", current.postalCode(), after.postalCode())
                .change("countryCode", current.countryCode(), after.countryCode())
                .change("isDefault", current.isDefault(), after.isDefault())
                .build());
        return after;
    }

    @Transactional
    public void deleteAddress(UUID partnerId, UUID addressId) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner = lock(companyId, partnerId);
        if (!partners.deleteAddress(companyId, partnerId, addressId)) {
            throw ApiException.notFound();
        }
        audit.record(AuditEvent.builder("DELETE", "partners")
                .entity("partner_address", addressId, partner.code())
                .build());
    }

    // ----------------------------------------------------------------------------- contacts

    @Transactional(readOnly = true)
    public List<PartnerViews.Contact> contacts(UUID partnerId) {
        UUID companyId = CurrentContext.requireCompany();
        partners.find(companyId, partnerId).orElseThrow(ApiException::notFound);
        return partners.contacts(companyId, partnerId);
    }

    @Transactional
    public PartnerViews.Contact addContact(UUID partnerId, PartnerCommands.Contact command) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner = lock(companyId, partnerId);
        if (command.isPrimary()) {
            partners.clearPrimaryContact(companyId, partnerId, null);
        }
        UUID id = partners.insertContact(companyId, partnerId, command, actor());
        audit.record(AuditEvent.builder("CREATE", "partners")
                .entity("partner_contact", id, partner.code())
                .detail("name", command.name())
                .build());
        return contact(companyId, partnerId, id);
    }

    @Transactional
    public PartnerViews.Contact patchContact(
            UUID partnerId, UUID contactId, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner = lock(companyId, partnerId);
        PartnerViews.Contact current = contact(companyId, partnerId, contactId);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, CONTACT_PATCHABLE);
        var name = patch.text("name", true, 200);
        var email = patch.text("email", false, 254, v -> v.matches(EMAIL) ? null : "must be an email address");
        var phone = patch.text("phone", false, 40);
        var role = patch.text("roleTitle", false, 100);
        var primary = patch.bool("isPrimary");
        patch.throwIfInvalid();
        PartnerCommands.Contact next = new PartnerCommands.Contact(
                name.orElse(current.name()),
                email.orElse(current.email()),
                phone.orElse(current.phone()),
                role.orElse(current.roleTitle()),
                Boolean.TRUE.equals(primary.orElse(current.isPrimary())));
        if (next.isPrimary()) {
            partners.clearPrimaryContact(companyId, partnerId, contactId);
        }
        if (!partners.updateContact(companyId, partnerId, contactId, current.version(), actor(), next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The contact was modified concurrently.");
        }
        PartnerViews.Contact after = contact(companyId, partnerId, contactId);
        audit.record(AuditEvent.builder("UPDATE", "partners")
                .entity("partner_contact", contactId, partner.code())
                .change("name", current.name(), after.name())
                .change("email", current.email(), after.email())
                .change("phone", current.phone(), after.phone())
                .change("roleTitle", current.roleTitle(), after.roleTitle())
                .change("isPrimary", current.isPrimary(), after.isPrimary())
                .build());
        return after;
    }

    @Transactional
    public void deleteContact(UUID partnerId, UUID contactId) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Partner partner = lock(companyId, partnerId);
        if (!partners.deleteContact(companyId, partnerId, contactId)) {
            throw ApiException.notFound();
        }
        audit.record(AuditEvent.builder("DELETE", "partners")
                .entity("partner_contact", contactId, partner.code())
                .build());
    }

    // ------------------------------------------------------------------------------ helpers

    private PartnerViews.Partner lock(UUID companyId, UUID id) {
        return partners.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
    }

    private void update(PartnerViews.Partner current, PartnerCommands.Partner next, String status) {
        if (!partners.update(current.companyId(), current.id(), current.version(), actor(), next, status)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The partner was modified concurrently.");
        }
    }

    private PartnerViews.Address address(UUID companyId, UUID partnerId, UUID id) {
        return partners.addresses(companyId, partnerId).stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    private PartnerViews.Contact contact(UUID companyId, UUID partnerId, UUID id) {
        return partners.contacts(companyId, partnerId).stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    private void checkCountry(String countryCode) {
        if (!org.countryExists(countryCode)) {
            throw ApiException.validationFailed(
                    "The address is invalid.",
                    List.of(FieldViolation.atPointer("/countryCode", "UNKNOWN_COUNTRY", "is not a known country")));
        }
    }

    private static PartnerCommands.Partner asCommand(PartnerViews.Partner p) {
        return new PartnerCommands.Partner(
                p.code(),
                p.name(),
                p.legalName(),
                p.partnerType(),
                p.taxRegistrationNo(),
                p.email(),
                p.phone(),
                p.website(),
                p.notes());
    }

    private static UUID actor() {
        return CurrentContext.requireActor().userId();
    }
}
