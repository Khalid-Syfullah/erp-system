package com.erp.platform.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class JsonConfigurationTest {

    record Payment(BigDecimal amount, String reference, int lines, boolean urgent, Kind kind, List<String> tags) {}

    enum Kind {
        INBOUND,
        OUTBOUND
    }

    private final JsonMapper mapper = mapper();

    private static JsonMapper mapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        JsonConfiguration.customize(builder);
        return builder.build();
    }

    @Test
    void readsDecimalsFromStringsAndTrimsText() {
        Payment p = mapper.readValue("""
                {"amount":"1234.50","reference":"  INV-1 ","lines":2,"urgent":true,"kind":"INBOUND","tags":[" a "]}""", Payment.class);

        assertThat(p.amount()).isEqualByComparingTo("1234.50");
        assertThat(p.amount().scale()).isEqualTo(2);
        assertThat(p.reference()).isEqualTo("INV-1");
        assertThat(p.tags()).containsExactly("a");
    }

    @Test
    void writesDecimalsAsPlainStrings() {
        String json =
                mapper.writeValueAsString(new Payment(new BigDecimal("1E+3"), "r", 1, false, Kind.OUTBOUND, List.of()));

        assertThat(json).contains("\"amount\":\"1000\"");
    }

    @Test
    void rejectsLossyOrAmbiguousInput() {
        String valid = """
                {"amount":"1.00","reference":"r","lines":1,"urgent":false,"kind":"INBOUND","tags":[]}""";
        assertThat(mapper.readValue(valid, Payment.class)).isNotNull();

        List<String> invalid = List.of(
                valid.replace("\"1.00\"", "1.00"), // decimal as JSON number
                valid.replace("\"1.00\"", "\"1e3\""), // exponent
                valid.replace("\"1.00\"", "\"NaN\""),
                valid.replace("\"1.00\"", "\"1,000.00\""),
                valid.replace("\"lines\":1", "\"lines\":\"1\""), // integer from string
                valid.replace("\"lines\":1", "\"lines\":1.5"), // float to int
                valid.replace("\"urgent\":false", "\"urgent\":\"false\""),
                valid.replace("\"urgent\":false", "\"urgent\":0"),
                valid.replace("\"kind\":\"INBOUND\"", "\"kind\":0"), // enum ordinal
                valid.replace("\"kind\":\"INBOUND\"", "\"kind\":\"inbound\""),
                valid.replace("\"reference\":\"r\"", "\"reference\":5"), // number as string
                valid.replace("\"reference\":\"r\"", "\"reference\":\"a\\u0000b\""), // NUL
                valid.replace("\"reference\":\"r\"", "\"reference\":\"a\\u001bb\""), // ESC
                valid.replace("}", ",\"extra\":1}"), // unknown property
                valid.replace("}", ",\"lines\":2}"), // duplicate key
                valid + " {}", // trailing tokens
                valid.replace("\"lines\":1,", "\"lines\":null,")); // null primitive

        for (String json : invalid) {
            assertThatThrownBy(() -> mapper.readValue(json, Payment.class))
                    .as(json)
                    .isInstanceOf(JacksonException.class);
        }
    }

    @Test
    void allowsMultilineText() {
        Payment p = mapper.readValue("""
                {"amount":"1","reference":"line1\\nline2\\tx","lines":1,"urgent":false,"kind":"INBOUND","tags":[]}""", Payment.class);

        assertThat(p.reference()).isEqualTo("line1\nline2\tx");
    }
}
