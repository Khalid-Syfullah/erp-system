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
}
