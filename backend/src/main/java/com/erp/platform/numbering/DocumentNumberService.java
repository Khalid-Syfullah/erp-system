package com.erp.platform.numbering;

import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.numbering.persistence.NumberingRepository;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gapless document numbers per company, document type and fiscal year (PRODUCT_SPEC.md G-6, ADR-012),
 * and the company's number formats. A number is taken inside the business transaction that leaves
 * draft, as the last lock (ARCHITECTURE.md §6.2), so a rollback returns it and no gap appears.
 */
@Service
public class DocumentNumberService {

    /** The effective format of one document type. */
    public record TypeFormat(String documentType, NumberFormat format, boolean companyDefined) {}

    /** All effective formats of the company with the settings version (ETag). */
    public record FormatsView(List<TypeFormat> formats, int version) {}

    private final NumberingRepository repository;
    private final Map<String, DocumentType> types;
    private final AuditPort audit;
    private final JsonMapper json;

    public DocumentNumberService(
            NumberingRepository repository, List<DocumentType> types, AuditPort audit, JsonMapper json) {
        this.repository = repository;
        this.types = types.stream().collect(Collectors.toMap(DocumentType::code, Function.identity(), (a, b) -> {
            throw new IllegalStateException("Duplicate document type " + a.code());
        }));
        this.audit = audit;
        this.json = json;
    }

    /** The next number, e.g. {@code SM-2026-000042}; must run in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID companyId, String documentType, String fiscalYear) {
        NumberFormat format = effective(companyId).getOrDefault(documentType, defaultFormat(documentType));
        long value = repository.allocate(
                companyId, documentType, fiscalYear, format.prefixFor(fiscalYear), format.padding());
        return format.render(fiscalYear, value);
    }

    @Transactional(readOnly = true)
    public FormatsView formats() {
        UUID companyId = CurrentContext.requireCompany();
        Map<String, NumberFormat> configured = configured(companyId);
        List<TypeFormat> all = new ArrayList<>();
        types.values().stream()
                .sorted(Comparator.comparing(DocumentType::code))
                .forEach(t -> all.add(new TypeFormat(
                        t.code(),
                        configured.getOrDefault(t.code(), new NumberFormat(t.defaultPrefix(), t.defaultPadding())),
                        configured.containsKey(t.code()))));
        return new FormatsView(
                all,
                repository
                        .settings(companyId)
                        .map(NumberingRepository.Settings::version)
                        .orElse(0));
    }

    /**
     * Replaces the company's formats (types left out return to their defaults). Existing sequences
     * continue: a new prefix applies to the next number of the current fiscal year.
     */
    @Transactional
    public FormatsView replace(@Nullable String ifMatch, Map<String, NumberFormat> formats) {
        UUID companyId = CurrentContext.requireCompany();
        int version = repository
                .settings(companyId)
                .map(NumberingRepository.Settings::version)
                .orElse(0);
        EntityTags.requireMatch(ifMatch, version);
        List<FieldViolation> unknown = formats.keySet().stream()
                .filter(t -> !types.containsKey(t))
                .map(t -> FieldViolation.atPointer(
                        "/formats/" + t, "UNKNOWN_DOCUMENT_TYPE", "is not a numbered document type"))
                .toList();
        if (!unknown.isEmpty()) {
            throw ApiException.validationFailed("The numbering settings are invalid.", unknown);
        }
        Map<String, NumberFormat> before = configured(companyId);
        Map<String, Map<String, Object>> stored = new LinkedHashMap<>();
        formats.forEach((type, f) -> stored.put(type, Map.of("prefix", f.prefix(), "padding", f.padding())));
        if (!repository.saveSettings(
                companyId,
                version,
                json.writeValueAsString(stored),
                CurrentContext.requireActor().userId())) {
            throw new ApiException(
                    PlatformErrorCode.VERSION_CONFLICT, "The numbering settings were modified concurrently.");
        }
        audit.record(AuditEvent.builder("CONFIG_CHANGE", "platform")
                .entity("numbering_settings", companyId, null)
                .change("formats", describe(before), describe(formats))
                .build());
        return formats();
    }

    private Map<String, NumberFormat> effective(UUID companyId) {
        return configured(companyId);
    }

    private Map<String, NumberFormat> configured(UUID companyId) {
        Map<String, NumberFormat> result = new LinkedHashMap<>();
        repository.settings(companyId).ifPresent(s -> {
            JsonNode root = json.readTree(s.formats());
            root.properties()
                    .forEach(e -> result.put(
                            e.getKey(),
                            new NumberFormat(
                                    e.getValue().get("prefix").asString(),
                                    e.getValue().get("padding").asInt())));
        });
        return result;
    }

    private NumberFormat defaultFormat(String documentType) {
        DocumentType type = types.get(documentType);
        if (type == null) {
            throw new IllegalArgumentException("Unknown document type " + documentType);
        }
        return new NumberFormat(type.defaultPrefix(), type.defaultPadding());
    }

    private static String describe(Map<String, NumberFormat> formats) {
        return formats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue().prefix() + "#"
                        + e.getValue().padding())
                .collect(Collectors.joining(", "));
    }
}
