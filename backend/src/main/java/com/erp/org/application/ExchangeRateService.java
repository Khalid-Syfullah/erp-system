package com.erp.org.application;

import com.erp.org.persistence.CompanyRepository;
import com.erp.org.persistence.ExchangeRateRepository;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Exchange rates of the active company (DATABASE.md §5.2). A rate converts one unit of a foreign
 * currency into the company's base currency; the base currency itself always converts at 1 and has
 * no rows. Documents snapshot the rate they use, so rates may be corrected or deleted.
 */
@Service
public class ExchangeRateService {

    /** numeric(19,10): at most 9 integer and 10 fraction digits. */
    public static final BigDecimal MIN_RATE = new BigDecimal("0.0000000001");

    public static final BigDecimal MAX_RATE = new BigDecimal("999999999.9999999999");
    public static final int RATE_SCALE = 10;

    static final Set<String> PATCHABLE = Set.of("rate");

    /** Result of a lookup; {@code rateDate} is the date of the rate used ({@code null} for the base currency). */
    public record Quote(
            String currencyCode,
            LocalDate date,
            BigDecimal rate,
            @Nullable LocalDate rateDate) {}

    private final ExchangeRateRepository rates;
    private final CompanyRepository companies;
    private final AuditPort audit;

    public ExchangeRateService(ExchangeRateRepository rates, CompanyRepository companies, AuditPort audit) {
        this.rates = rates;
        this.companies = companies;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ExchangeRateView> list(ListQuery query) {
        return rates.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public ExchangeRateView get(UUID id) {
        return rates.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    /** Latest rate on or before the date; {@code 422 EXCHANGE_RATE_MISSING} when there is none. */
    @Transactional(readOnly = true)
    public Quote lookup(String currencyCode, LocalDate date) {
        UUID companyId = CurrentContext.requireCompany();
        if (baseCurrency(companyId).equals(currencyCode)) {
            return new Quote(currencyCode, date, BigDecimal.ONE, null);
        }
        return rates.latestOnOrBefore(companyId, currencyCode, date)
                .map(r -> new Quote(currencyCode, date, r.rate(), r.rateDate()))
                .orElseThrow(() -> new ApiException(
                        OrgErrorCode.EXCHANGE_RATE_MISSING,
                        "No exchange rate for " + currencyCode + " on or before " + date + "."));
    }

    @Transactional
    public ExchangeRateView create(String currencyCode, LocalDate rateDate, BigDecimal rate) {
        UUID companyId = CurrentContext.requireCompany();
        List<FieldViolation> violations = new ArrayList<>();
        if (!companies.activeCurrencyExists(currencyCode)) {
            violations.add(
                    FieldViolation.atPointer("/currencyCode", "UNKNOWN_CURRENCY", "is not an active currency code"));
        } else if (baseCurrency(companyId).equals(currencyCode)) {
            violations.add(FieldViolation.atPointer(
                    "/currencyCode", "BASE_CURRENCY", "is the company's base currency, whose rate is always 1"));
        }
        if (rate.stripTrailingZeros().scale() > RATE_SCALE) {
            violations.add(FieldViolation.atPointer(
                    "/rate", "INVALID_VALUE", "must have at most " + RATE_SCALE + " decimal places"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The exchange rate is invalid.", violations);
        }
        UUID id = rates.insert(
                companyId,
                currencyCode,
                rateDate,
                rate,
                CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "org")
                .entity("exchange_rate", id, currencyCode + " " + rateDate)
                .detail("currencyCode", currencyCode)
                .detail("rateDate", rateDate.toString())
                .detail("rate", rate.toPlainString())
                .build());
        return get(id);
    }

    @Transactional
    public ExchangeRateView patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        ExchangeRateView current = rates.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        MergePatch.Member<BigDecimal> rate = patch.decimal("rate", MIN_RATE, MAX_RATE, RATE_SCALE);
        patch.throwIfInvalid();
        if (rate.present()
                && !rates.updateRate(
                        companyId,
                        id,
                        current.version(),
                        CurrentContext.requireActor().userId(),
                        rate.value())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The exchange rate was modified concurrently.");
        }
        ExchangeRateView after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "org")
                .entity("exchange_rate", id, after.currencyCode() + " " + after.rateDate())
                .change("rate", current.rate().toPlainString(), after.rate().toPlainString())
                .build());
        return after;
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ExchangeRateView current = rates.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!rates.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The exchange rate was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "org")
                .entity("exchange_rate", id, current.currencyCode() + " " + current.rateDate())
                .detail("rate", current.rate().toPlainString())
                .build());
    }

    private String baseCurrency(UUID companyId) {
        return companies.find(companyId).orElseThrow(ApiException::notFound).baseCurrency();
    }
}
