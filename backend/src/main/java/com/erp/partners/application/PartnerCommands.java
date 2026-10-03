package com.erp.partners.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Validated write models of the Partners module (built by the web layer from request DTOs). */
public final class PartnerCommands {

    private PartnerCommands() {}

    public record Partner(
            String code,
            String name,
            @Nullable String legalName,
            String partnerType,
            @Nullable String taxRegistrationNo,
            @Nullable String email,
            @Nullable String phone,
            @Nullable String website,
            @Nullable String notes) {}

    public record Address(
            String addressType,
            String line1,
            @Nullable String line2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            String countryCode,
            boolean isDefault) {}

    public record Contact(
            String name,
            @Nullable String email,
            @Nullable String phone,
            @Nullable String roleTitle,
            boolean isPrimary) {}

    public record BankAccount(
            String bankName,
            String accountHolder,
            String accountNumber,
            @Nullable String iban,
            @Nullable String swiftBic,
            @Nullable String currencyCode,
            boolean isDefault) {}

    public record Supplier(
            @Nullable UUID supplierGroupId,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable UUID defaultTaxCodeId,
            @Nullable Integer leadTimeDays) {}

    public record Group(String code, String name, String appliesTo) {}
}
