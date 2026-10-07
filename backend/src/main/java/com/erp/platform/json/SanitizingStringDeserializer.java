package com.erp.platform.json;

import java.text.Normalizer;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.std.StdScalarDeserializer;

/**
 * Reads strings only from JSON strings (no numbers or booleans coerced to text), trims surrounding
 * whitespace and rejects control characters other than tab, line feed and carriage return
 * (API.md §7). NUL in particular is rejected because PostgreSQL text cannot store it. Text is stored
 * in Unicode NFC, so that Bangla typed with different code-point sequences (য় as one or two code
 * points) is stored, compared and searched alike (docs/LOCALIZATION.md). Fields marked {@link RawText}
 * keep their exact value (no trimming, no normalization).
 */
final class SanitizingStringDeserializer extends StdScalarDeserializer<String> {

    private final boolean trim;

    SanitizingStringDeserializer() {
        this(true);
    }

    private SanitizingStringDeserializer(boolean trim) {
        super(String.class);
        this.trim = trim;
    }

    @Override
    public ValueDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        boolean raw = property != null && property.getAnnotation(RawText.class) != null;
        return raw == !trim ? this : new SanitizingStringDeserializer(!raw);
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
        return trim ? Normalizer.normalize(value.strip(), Normalizer.Form.NFC) : value;
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
