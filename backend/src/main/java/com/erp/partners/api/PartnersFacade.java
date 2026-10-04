package com.erp.partners.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read access to partners for document modules. Every method runs in the caller's transaction and
 * company context. A partner that is not {@code ACTIVE} cannot be used on new documents; open
 * documents can still be completed (PRODUCT_SPEC.md §5).
 */
public interface PartnersFacade {

    /**
     * The supplier, with its partner row locked {@code FOR SHARE} until the caller's transaction ends,
     * so that a concurrent block or deactivation waits and then sees the new use. Empty if the partner
     * has no supplier profile in the company.
     */
    Optional<SupplierInfo> supplierForUse(UUID supplierId);

    Optional<SupplierInfo> supplier(UUID supplierId);

    /** The customer, with its partner row locked {@code FOR SHARE}; empty without a customer profile. */
    Optional<CustomerInfo> customerForUse(UUID customerId);

    Optional<CustomerInfo> customer(UUID customerId);

    /** The partner's default address of the type ({@code BILLING}, {@code SHIPPING}, {@code OTHER}). */
    Optional<AddressInfo> defaultAddress(UUID partnerId, String addressType);

    /** Whether the group is an active customer group of the company (price lists, SAL-1). */
    boolean customerGroupUsable(UUID groupId);

    /** A partner group of the company (any status): Accounting validates group-scoped mappings. */
    Optional<GroupInfo> group(UUID groupId);

    record GroupInfo(UUID id, String code, String appliesTo, boolean active) {}

    /** Code and name of partners by ID (unknown IDs are skipped), for display. */
    Map<UUID, PartnerSummary> partners(Collection<UUID> partnerIds);

    record PartnerSummary(UUID id, String code, String name, String status) {}

    /** A partner with its supplier profile; amounts on supplier documents default from it. */
    record SupplierInfo(
            UUID partnerId,
            String code,
            String name,
            @Nullable String legalName,
            String status,
            @Nullable String taxRegistrationNo,
            @Nullable UUID supplierGroupId,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable UUID defaultTaxCodeId,
            @Nullable Integer leadTimeDays) {

        /** Whether new documents may name this supplier. */
        public boolean usable() {
            return "ACTIVE".equals(status);
        }
    }

    /** A partner with its customer profile; {@code creditLimit} in base currency, {@code null}: none. */
    record CustomerInfo(
            UUID partnerId,
            String code,
            String name,
            @Nullable String legalName,
            String status,
            @Nullable String taxRegistrationNo,
            @Nullable UUID customerGroupId,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable UUID defaultTaxCodeId,
            java.math.@Nullable BigDecimal creditLimit,
            boolean onHold) {

        public boolean usable() {
            return "ACTIVE".equals(status);
        }
    }

    record AddressInfo(
            String addressType,
            String line1,
            @Nullable String line2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            String countryCode) {}
}
