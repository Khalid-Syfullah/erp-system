package com.erp.partners.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Partners module. */
public final class PartnerViews {

    private PartnerViews() {}

    public record Partner(
            UUID id,
            UUID companyId,
            String code,
            String name,
            @Nullable String legalName,
            String partnerType,
            @Nullable String taxRegistrationNo,
            @Nullable String email,
            @Nullable String phone,
            @Nullable String website,
            String status,
            @Nullable String notes,
            boolean isSupplier,
            boolean isCustomer,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}

    /** A partner with its addresses, contacts and profiles. */
    public record PartnerDetail(
            Partner partner,
            List<Address> addresses,
            List<Contact> contacts,
            @Nullable Supplier supplier,
            @Nullable Customer customer) {}

    public record Address(
            UUID id,
            UUID partnerId,
            String addressType,
            String line1,
            @Nullable String line2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            String countryCode,
            boolean isDefault,
            int version) {}

    public record Contact(
            UUID id,
            UUID partnerId,
            String name,
            @Nullable String email,
            @Nullable String phone,
            @Nullable String roleTitle,
            boolean isPrimary,
            int version) {}

    /** A bank account as shown by default: the number masked to its last four characters. */
    public record BankAccount(
            UUID id,
            UUID partnerId,
            String bankName,
            String accountHolder,
            String last4,
            boolean hasIban,
            @Nullable String swiftBic,
            @Nullable String currencyCode,
            boolean isDefault,
            OffsetDateTime createdAt,
            int version) {}

    /** The stored (encrypted) form, for the reveal operation only. */
    public record EncryptedBankAccount(
            UUID id, UUID partnerId, byte[] accountNumberEncrypted, byte @Nullable [] ibanEncrypted, int keyVersion) {}

    public record Supplier(
            UUID partnerId,
            @Nullable UUID supplierGroupId,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable UUID defaultTaxCodeId,
            @Nullable Integer leadTimeDays,
            OffsetDateTime updatedAt,
            int version) {}

    /** A row of the supplier list: the partner with its profile. */
    public record SupplierListItem(Partner partner, Supplier supplier) {}

    /** {@code creditLimit} is in the company's base currency; {@code null}: no limit. */
    public record Customer(
            UUID partnerId,
            @Nullable UUID customerGroupId,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable UUID defaultTaxCodeId,
            java.math.@Nullable BigDecimal creditLimit,
            boolean onHold,
            OffsetDateTime updatedAt,
            int version) {}

    public record CustomerListItem(Partner partner, Customer customer) {}

    public record Group(
            UUID id,
            String code,
            String name,
            String appliesTo,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {}
}
