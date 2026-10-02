package com.erp.platform.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;

/**
 * Reads strings only from JSON strings (no numbers or booleans coerced to text), trims surrounding
 * whitespace and rejects control characters other than tab, line feed and carriage return
 * (API.md §7). NUL in particular is rejected because PostgreSQL text cannot store it.
 *
 * <p>Fields whose exact value matters (passwords, from Phase 3) will need an explicit opt-out from
 * trimming.
 */
final class SanitizingStringDeserializer extends StdScalarDeserializer<String> {

    SanitizingStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(this, "Expected a JSON string");
        }
        String value = parser.getString();
        if (containsForbiddenCharacter(value)) {
            return context.reportInputMismatch(this, "String contains control characters");
        }
        return value.strip();
    }

    static boolean containsForbiddenCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || (c >= 0x7F && c <= 0x9F)) {
                return true;
            }
        }
        return false;
    }
}
