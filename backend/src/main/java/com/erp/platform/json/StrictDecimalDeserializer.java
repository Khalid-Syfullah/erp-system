package com.erp.platform.json;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;

/**
 * Reads {@link BigDecimal} only from JSON strings in plain decimal notation (API.md §11): no JSON
 * numbers (which clients may have passed through binary floating point), no exponents, no
 * {@code NaN}/{@code Infinity}, at most 38 significant characters.
 */
final class StrictDecimalDeserializer extends StdScalarDeserializer<BigDecimal> {

    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]{0,30})(\\.[0-9]{1,12})?$");

    StrictDecimalDeserializer() {
        super(BigDecimal.class);
    }

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(this, "Decimal values must be JSON strings");
        }
        String text = parser.getString().strip();
        if (!PLAIN_DECIMAL.matcher(text).matches()) {
            return context.reportInputMismatch(this, "Not a plain decimal number");
        }
        return new BigDecimal(text);
    }
}
