package com.erp.reporting.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.JSONB;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Report parameters (name → string value) between {@code jsonb} columns and maps. */
@Component
class ReportingJson {

    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {};

    private final JsonMapper json;

    ReportingJson(JsonMapper json) {
        this.json = json;
    }

    JSONB write(Map<String, String> parameters) {
        return JSONB.jsonb(json.writeValueAsString(parameters));
    }

    Map<String, String> read(@Nullable JSONB value) {
        return value == null ? Map.of() : json.readValue(value.data(), MAP);
    }
}
