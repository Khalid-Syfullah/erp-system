package com.erp.partners.web;

import com.erp.partners.PartnersPermissions;
import com.erp.partners.application.PartnerCommands;
import com.erp.partners.application.PartnerListings;
import com.erp.partners.application.PartnerService;
import com.erp.partners.domain.PartnerStatus;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Partners, their status, addresses and contacts (API.md §17.4). */
@RestController
class PartnerController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final PartnerService partners;
    private final ListQueryParser parser;

    PartnerController(PartnerService partners, ListQueryParser parser) {
        this.partners = partners;
        this.parser = parser;
    }

    record PartnerRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9._/-]{0,29}$") String code,

            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) @Nullable String legalName,

            @NotBlank @Pattern(regexp = "^(ORGANIZATION|INDIVIDUAL)$")
            String partnerType,

            @Size(max = 50) @Nullable String taxRegistrationNo,
            @Email @Size(max = 254) @Nullable String email,
            @Size(max = 40) @Nullable String phone,
            @Size(max = 200) @Nullable String website,
            @Size(max = 4000) @Nullable String notes) {}

    record AddressRequest(
            @NotBlank @Pattern(regexp = "^(BILLING|SHIPPING|OTHER)$")
            String addressType,

            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) @Nullable String line2,
            @Size(max = 100) @Nullable String city,
            @Size(max = 100) @Nullable String region,
            @Size(max = 20) @Nullable String postalCode,

            @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @Nullable Boolean isDefault) {}

    record ContactRequest(
            @NotBlank @Size(max = 200) String name,
            @Email @Size(max = 254) @Nullable String email,
            @Size(max = 40) @Nullable String phone,
            @Size(max = 100) @Nullable String roleTitle,
            @Nullable Boolean isPrimary) {}

    @RequiresPermission(PartnersPermissions.PARTNER_READ)
    @GetMapping(C + "/partners")
    PageResponse<PartnersResponses.Partner> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return partners.list(parser.parse(parameters, PartnerListings.PARTNERS))
                .map(PartnersResponses.Partner::summary);
    }

    @RequiresPermission(PartnersPermissions.PARTNER_READ)
    @GetMapping(C + "/partners/{partnerId}")
    ResponseEntity<PartnersResponses.Partner> get(@PathVariable UUID companyId, @PathVariable UUID partnerId) {
        return PartnersResponses.Partner.entity(partners.get(partnerId));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PostMapping(C + "/partners")
    ResponseEntity<PartnersResponses.Partner> create(
            @PathVariable UUID companyId, @Valid @RequestBody PartnerRequest request) {
        var created = partners.create(new PartnerCommands.Partner(
                request.code(),
                request.name().strip(),
                blankToNull(request.legalName()),
                request.partnerType(),
                blankToNull(request.taxRegistrationNo()),
                blankToNull(request.email()),
                blankToNull(request.phone()),
                blankToNull(request.website()),
                blankToNull(request.notes())));
        ResponseEntity<PartnersResponses.Partner> body = PartnersResponses.Partner.entity(created);
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/partners/"
                        + created.partner().id()))
                .headers(body.getHeaders())
                .body(body.getBody());
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PatchMapping(path = C + "/partners/{partnerId}", consumes = PartnersResponses.MERGE_PATCH)
    ResponseEntity<PartnersResponses.Partner> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID partnerId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return PartnersResponses.Partner.entity(partners.patch(partnerId, ifMatch, patch));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PostMapping(C + "/partners/{partnerId}/activate")
    ResponseEntity<PartnersResponses.Partner> activate(
            @PathVariable UUID companyId,
            @PathVariable UUID partnerId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return PartnersResponses.Partner.entity(
                partners.changeStatus(partnerId, ifMatch, PartnerStatus.Action.ACTIVATE));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PostMapping(C + "/partners/{partnerId}/deactivate")
    ResponseEntity<PartnersResponses.Partner> deactivate(
            @PathVariable UUID companyId,
            @PathVariable UUID partnerId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return PartnersResponses.Partner.entity(
                partners.changeStatus(partnerId, ifMatch, PartnerStatus.Action.DEACTIVATE));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PostMapping(C + "/partners/{partnerId}/block")
    ResponseEntity<PartnersResponses.Partner> block(
            @PathVariable UUID companyId,
            @PathVariable UUID partnerId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return PartnersResponses.Partner.entity(partners.changeStatus(partnerId, ifMatch, PartnerStatus.Action.BLOCK));
    }

    // ---------------------------------------------------------------------------- addresses

    @RequiresPermission(PartnersPermissions.PARTNER_READ)
    @GetMapping(C + "/partners/{partnerId}/addresses")
    PartnersResponses.ListResponse<PartnersResponses.Address> addresses(
            @PathVariable UUID companyId, @PathVariable UUID partnerId) {
        return new PartnersResponses.ListResponse<>(partners.addresses(partnerId).stream()
                .map(PartnersResponses.Address::from)
                .toList());
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PostMapping(C + "/partners/{partnerId}/addresses")
    ResponseEntity<PartnersResponses.Address> addAddress(
            @PathVariable UUID companyId, @PathVariable UUID partnerId, @Valid @RequestBody AddressRequest request) {
        var created = partners.addAddress(
                partnerId,
                new PartnerCommands.Address(
                        request.addressType(),
                        request.line1().strip(),
                        blankToNull(request.line2()),
                        blankToNull(request.city()),
                        blankToNull(request.region()),
                        blankToNull(request.postalCode()),
                        request.countryCode(),
                        Boolean.TRUE.equals(request.isDefault())));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/partners/" + partnerId
                        + "/addresses/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(PartnersResponses.Address.from(created));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PatchMapping(path = C + "/partners/{partnerId}/addresses/{addressId}", consumes = PartnersResponses.MERGE_PATCH)
    ResponseEntity<PartnersResponses.Address> patchAddress(
            @PathVariable UUID companyId,
            @PathVariable UUID partnerId,
            @PathVariable UUID addressId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        var updated = partners.patchAddress(partnerId, addressId, ifMatch, patch);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(updated.version()))
                .body(PartnersResponses.Address.from(updated));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @DeleteMapping(C + "/partners/{partnerId}/addresses/{addressId}")
    ResponseEntity<Void> deleteAddress(
            @PathVariable UUID companyId, @PathVariable UUID partnerId, @PathVariable UUID addressId) {
        partners.deleteAddress(partnerId, addressId);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------------------- contacts

    @RequiresPermission(PartnersPermissions.PARTNER_READ)
    @GetMapping(C + "/partners/{partnerId}/contacts")
    PartnersResponses.ListResponse<PartnersResponses.Contact> contacts(
            @PathVariable UUID companyId, @PathVariable UUID partnerId) {
        return new PartnersResponses.ListResponse<>(partners.contacts(partnerId).stream()
                .map(PartnersResponses.Contact::from)
                .toList());
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PostMapping(C + "/partners/{partnerId}/contacts")
    ResponseEntity<PartnersResponses.Contact> addContact(
            @PathVariable UUID companyId, @PathVariable UUID partnerId, @Valid @RequestBody ContactRequest request) {
        var created = partners.addContact(
                partnerId,
                new PartnerCommands.Contact(
                        request.name().strip(),
                        blankToNull(request.email()),
                        blankToNull(request.phone()),
                        blankToNull(request.roleTitle()),
                        Boolean.TRUE.equals(request.isPrimary())));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/partners/" + partnerId
                        + "/contacts/" + created.id()))
                .eTag(EntityTags.forVersion(created.version()))
                .body(PartnersResponses.Contact.from(created));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @PatchMapping(path = C + "/partners/{partnerId}/contacts/{contactId}", consumes = PartnersResponses.MERGE_PATCH)
    ResponseEntity<PartnersResponses.Contact> patchContact(
            @PathVariable UUID companyId,
            @PathVariable UUID partnerId,
            @PathVariable UUID contactId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        var updated = partners.patchContact(partnerId, contactId, ifMatch, patch);
        return ResponseEntity.ok()
                .eTag(EntityTags.forVersion(updated.version()))
                .body(PartnersResponses.Contact.from(updated));
    }

    @RequiresPermission(PartnersPermissions.PARTNER_MANAGE)
    @DeleteMapping(C + "/partners/{partnerId}/contacts/{contactId}")
    ResponseEntity<Void> deleteContact(
            @PathVariable UUID companyId, @PathVariable UUID partnerId, @PathVariable UUID contactId) {
        partners.deleteContact(partnerId, contactId);
        return ResponseEntity.noContent().build();
    }

    static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
