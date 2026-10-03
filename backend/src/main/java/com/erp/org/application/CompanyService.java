package com.erp.org.application;

import com.erp.org.persistence.CompanyRepository;
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
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Company administration (API.md §17.3). Code, country and base currency are immutable through the
 * API: the base currency becomes immutable after the first posting anyway (PRODUCT_SPEC.md §4.2), and
 * changing them is a data-migration task.
 */
@Service
public class CompanyService {

    static final Set<String> PATCHABLE = Set.of(
            "legalName",
            "displayName",
            "taxRegistrationNo",
            "registrationNo",
            "timezone",
            "fiscalYearStartMonth",
            "addressLine1",
            "addressLine2",
            "city",
            "region",
            "postalCode",
            "roundingMode",
            "taxRounding",
            "status");

    private final CompanyRepository companies;
    private final AuditPort audit;

    public CompanyService(CompanyRepository companies, AuditPort audit) {
        this.companies = companies;
        this.audit = audit;
    }

    @Transactional
    public CompanyView create(CompanyCommands.Create command) {
        List<FieldViolation> violations = new ArrayList<>();
        if (!companies.countryExists(command.countryCode())) {
            violations.add(FieldViolation.atPointer("/countryCode", "UNKNOWN_COUNTRY", "is not a known country code"));
        }
        if (!companies.activeCurrencyExists(command.baseCurrency())) {
            violations.add(
                    FieldViolation.atPointer("/baseCurrency", "UNKNOWN_CURRENCY", "is not an active currency code"));
        }
        String zoneError = zoneError(command.timezone());
        if (zoneError != null) {
            violations.add(FieldViolation.atPointer("/timezone", "INVALID_VALUE", zoneError));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The company is invalid.", violations);
        }
        UUID actor = CurrentContext.requireActor().userId();
        UUID id = companies.insert(command, actor);
        audit.record(AuditEvent.builder("CREATE", "org")
                .entity("company", id, command.code())
                .company(id)
                .detail("code", command.code())
                .detail("baseCurrency", command.baseCurrency())
                .build());
        return companies.find(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public CompanyView get(UUID companyId) {
        return companies.find(companyId).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public PageResponse<CompanyView> list(ListQuery query) {
        return companies.list(query);
    }

    @Transactional
    public CompanyView patch(UUID companyId, @Nullable String ifMatch, JsonNode document) {
        CompanyView current = get(companyId);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        MergePatch.Member<String> legalName = patch.text("legalName", true, 200);
        MergePatch.Member<String> displayName = patch.text("displayName", true, 100);
        MergePatch.Member<String> taxRegistrationNo = patch.text("taxRegistrationNo", false, 50);
        MergePatch.Member<String> registrationNo = patch.text("registrationNo", false, 50);
        MergePatch.Member<String> timezone = patch.text("timezone", true, 64, CompanyService::zoneError);
        MergePatch.Member<Integer> fiscalStart = patch.integer("fiscalYearStartMonth", 1, 12);
        MergePatch.Member<String> line1 = patch.text("addressLine1", false, 200);
        MergePatch.Member<String> line2 = patch.text("addressLine2", false, 200);
        MergePatch.Member<String> city = patch.text("city", false, 100);
        MergePatch.Member<String> region = patch.text("region", false, 100);
        MergePatch.Member<String> postalCode = patch.text("postalCode", false, 20);
        MergePatch.Member<String> roundingMode = patch.text(
                "roundingMode",
                true,
                10,
                s -> Set.of("HALF_UP", "HALF_EVEN").contains(s) ? null : "must be HALF_UP or HALF_EVEN");
        MergePatch.Member<String> taxRounding = patch.text(
                "taxRounding",
                true,
                12,
                s -> Set.of("PER_LINE", "PER_DOCUMENT").contains(s) ? null : "must be PER_LINE or PER_DOCUMENT");
        MergePatch.Member<String> status = patch.text(
                "status",
                true,
                10,
                s -> Set.of("ACTIVE", "INACTIVE").contains(s) ? null : "must be ACTIVE or INACTIVE");
        patch.throwIfInvalid();

        UUID actor = CurrentContext.requireActor().userId();
        boolean updated = companies.update(
                companyId,
                current.version(),
                actor,
                new CompanyCommands.UpdateCompany(
                        legalName.orElse(current.legalName()),
                        displayName.orElse(current.displayName()),
                        taxRegistrationNo.orElse(current.taxRegistrationNo()),
                        registrationNo.orElse(current.registrationNo()),
                        timezone.orElse(current.timezone()),
                        fiscalStart.orElse(current.fiscalYearStartMonth()),
                        line1.orElse(current.addressLine1()),
                        line2.orElse(current.addressLine2()),
                        city.orElse(current.city()),
                        region.orElse(current.region()),
                        postalCode.orElse(current.postalCode()),
                        roundingMode.orElse(current.roundingMode()),
                        taxRounding.orElse(current.taxRounding()),
                        status.orElse(current.status())));
        if (!updated) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The company was modified concurrently.");
        }
        CompanyView after = get(companyId);
        audit.record(AuditEvent.builder("UPDATE", "org")
                .entity("company", companyId, after.code())
                .company(companyId)
                .change("legalName", current.legalName(), after.legalName())
                .change("displayName", current.displayName(), after.displayName())
                .change("taxRegistrationNo", current.taxRegistrationNo(), after.taxRegistrationNo())
                .change("registrationNo", current.registrationNo(), after.registrationNo())
                .change("timezone", current.timezone(), after.timezone())
                .change("fiscalYearStartMonth", current.fiscalYearStartMonth(), after.fiscalYearStartMonth())
                .change("addressLine1", current.addressLine1(), after.addressLine1())
                .change("addressLine2", current.addressLine2(), after.addressLine2())
                .change("city", current.city(), after.city())
                .change("region", current.region(), after.region())
                .change("postalCode", current.postalCode(), after.postalCode())
                .change("roundingMode", current.roundingMode(), after.roundingMode())
                .change("taxRounding", current.taxRounding(), after.taxRounding())
                .change("status", current.status(), after.status())
                .build());
        return after;
    }

    static @Nullable String zoneError(String zone) {
        try {
            ZoneId parsed = ZoneId.of(zone);
            return ZoneId.getAvailableZoneIds().contains(parsed.getId())
                    ? null
                    : "must be an IANA time zone, e.g. Europe/Berlin";
        } catch (DateTimeException e) {
            return "must be an IANA time zone, e.g. Europe/Berlin";
        }
    }
}
