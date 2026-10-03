package com.erp.sales.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.JSONB;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Address snapshots (G-10) between {@code jsonb} columns and maps. */
@Component
class AddressJson {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final JsonMapper json;

    AddressJson(JsonMapper json) {
        this.json = json;
    }

    JSONB write(@Nullable Map<String, Object> address) {
        return JSONB.jsonb(json.writeValueAsString(address == null ? Map.of() : address));
    }

    Map<String, Object> read(@Nullable JSONB value) {
        if (value == null) {
            return Map.of();
        }
        return json.readValue(value.data(), MAP);
    }
}
