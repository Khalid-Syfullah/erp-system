package com.erp.partners.web;

import com.erp.partners.application.PartnerViews;
import com.erp.partners.domain.BankAccountNumbers;
import com.erp.platform.web.EntityTags;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

/** Response bodies of the Partners endpoints (API.md §17.4). */
final class PartnersResponses {

    static final String MERGE_PATCH = "application/merge-patch+json";

    private PartnersResponses() {}

    record ListResponse<T>(List<T> data) {}

    record Partner(
            UUID id,
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
            @Nullable List<Address> addresses,
            @Nullable List<Contact> contacts,
            @Nullable Supplier supplierProfile,
            @Nullable Customer customerProfile,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {

        static Partner summary(PartnerViews.Partner p) {
            return new Partner(
                    p.id(),
                    p.code(),
                    p.name(),
                    p.legalName(),
                    p.partnerType(),
                    p.taxRegistrationNo(),
                    p.email(),
                    p.phone(),
                    p.website(),
                    p.status(),
                    p.notes(),
                    p.isSupplier(),
                    p.isCustomer(),
                    null,
                    null,
                    null,
                    null,
                    p.createdAt(),
                    p.updatedAt(),
                    p.version());
        }

        static ResponseEntity<Partner> entity(PartnerViews.PartnerDetail d) {
            PartnerViews.Partner p = d.partner();
            Partner body = new Partner(
                    p.id(),
                    p.code(),
                    p.name(),
                    p.legalName(),
                    p.partnerType(),
                    p.taxRegistrationNo(),
                    p.email(),
                    p.phone(),
                    p.website(),
                    p.status(),
                    p.notes(),
                    p.isSupplier(),
                    p.isCustomer(),
                    d.addresses().stream().map(Address::from).toList(),
                    d.contacts().stream().map(Contact::from).toList(),
                    d.supplier() == null ? null : Supplier.from(d.supplier()),
                    d.customer() == null ? null : Customer.from(d.customer()),
                    p.createdAt(),
                    p.updatedAt(),
                    p.version());
            return ResponseEntity.ok().eTag(EntityTags.forVersion(p.version())).body(body);
        }
    }

    record Address(
            UUID id,
            String addressType,
            String line1,
            @Nullable String line2,
            @Nullable String city,
            @Nullable String region,
            @Nullable String postalCode,
            String countryCode,
            boolean isDefault,
            int version) {
        static Address from(PartnerViews.Address a) {
            return new Address(
                    a.id(),
                    a.addressType(),
                    a.line1(),
                    a.line2(),
                    a.city(),
                    a.region(),
                    a.postalCode(),
                    a.countryCode(),
                    a.isDefault(),
                    a.version());
        }
    }

    record Contact(
            UUID id,
            String name,
            @Nullable String email,
            @Nullable String phone,
            @Nullable String roleTitle,
            boolean isPrimary,
            int version) {
        static Contact from(PartnerViews.Contact c) {
            return new Contact(c.id(), c.name(), c.email(), c.phone(), c.roleTitle(), c.isPrimary(), c.version());
        }
    }

    /** Masked by default (SECURITY.md §7.4). */
    record BankAccount(
            UUID id,
            String bankName,
            String accountHolder,
            String accountNumberMasked,
            String last4,
            boolean hasIban,
            @Nullable String swiftBic,
            @Nullable String currencyCode,
            boolean isDefault,
            OffsetDateTime createdAt) {
        static BankAccount from(PartnerViews.BankAccount b) {
            return new BankAccount(
                    b.id(),
                    b.bankName(),
                    b.accountHolder(),
                    BankAccountNumbers.masked(b.last4()),
                    b.last4(),
                    b.hasIban(),
                    b.swiftBic(),
                    b.currencyCode(),
                    b.isDefault(),
                    b.createdAt());
        }
    }

    record RevealedBankAccount(
            UUID id, String accountNumber, @Nullable String iban) {}

    record Supplier(
            UUID partnerId,
            @Nullable UUID supplierGroupId,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable UUID defaultTaxCodeId,
            @Nullable Integer leadTimeDays,
            OffsetDateTime updatedAt,
            int version) {
        static Supplier from(PartnerViews.Supplier s) {
            return new Supplier(
                    s.partnerId(),
                    s.supplierGroupId(),
                    s.currencyCode(),
                    s.paymentTermsId(),
                    s.defaultTaxCodeId(),
                    s.leadTimeDays(),
                    s.updatedAt(),
                    s.version());
        }
    }

    record Customer(
            UUID partnerId,
            @Nullable UUID customerGroupId,
            String currencyCode,
            @Nullable UUID paymentTermsId,
            @Nullable UUID defaultTaxCodeId,
            java.math.@Nullable BigDecimal creditLimit,
            boolean isOnHold,
            OffsetDateTime updatedAt,
            int version) {
        static Customer from(PartnerViews.Customer c) {
            return new Customer(
                    c.partnerId(),
                    c.customerGroupId(),
                    c.currencyCode(),
                    c.paymentTermsId(),
                    c.defaultTaxCodeId(),
                    c.creditLimit(),
                    c.onHold(),
                    c.updatedAt(),
                    c.version());
        }
    }

    /** A row of {@code GET {c}/customers}. */
    record CustomerRow(
            UUID id,
            String code,
            String name,
            String status,
            @Nullable String taxRegistrationNo,
            Customer profile) {
        static CustomerRow from(PartnerViews.CustomerListItem item) {
            PartnerViews.Partner p = item.partner();
            return new CustomerRow(
                    p.id(), p.code(), p.name(), p.status(), p.taxRegistrationNo(), Customer.from(item.customer()));
        }
    }

    /** A row of {@code GET {c}/suppliers}. */
    record SupplierRow(
            UUID id,
            String code,
            String name,
            String status,
            @Nullable String taxRegistrationNo,
            Supplier profile) {
        static SupplierRow from(PartnerViews.SupplierListItem item) {
            PartnerViews.Partner p = item.partner();
            return new SupplierRow(
                    p.id(), p.code(), p.name(), p.status(), p.taxRegistrationNo(), Supplier.from(item.supplier()));
        }
    }

    record Group(
            UUID id,
            String code,
            String name,
            String appliesTo,
            boolean isActive,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version) {
        static Group from(PartnerViews.Group g) {
            return new Group(
                    g.id(), g.code(), g.name(), g.appliesTo(), g.isActive(), g.createdAt(), g.updatedAt(), g.version());
        }

        static ResponseEntity<Group> entity(PartnerViews.Group g) {
            return ResponseEntity.ok().eTag(EntityTags.forVersion(g.version())).body(from(g));
        }
    }
}
