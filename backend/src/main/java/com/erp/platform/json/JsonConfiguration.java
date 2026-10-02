package com.erp.platform.json;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

/**
 * Strict JSON handling for the API (API.md §7, §11):
 *
 * <ul>
 *   <li>unknown properties, duplicate keys and trailing tokens are rejected (no mass assignment);
 *   <li>no silent scalar coercion ({@code "5"} is not an integer, {@code 1.5} is not an integer);
 *   <li>{@link BigDecimal} is read only from JSON strings and written as plain strings, so money
 *       never passes through binary floating point;
 *   <li>strings are trimmed and must not contain control characters.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer erpJsonMapperCustomizer() {
        return JsonConfiguration::customize;
    }

    public static void customize(JsonMapper.Builder builder) {
        builder.enable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION, true)
                .configure(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN, true)
                .withCoercionConfig(
                        LogicalType.Integer,
                        c -> c.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
                .withCoercionConfig(
                        LogicalType.Boolean,
                        c -> c.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
                .withConfigOverride(
                        BigDecimal.class, o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
                .addModule(new SimpleModule("erp-strict-scalars")
                        .addDeserializer(BigDecimal.class, new StrictDecimalDeserializer())
                        .addDeserializer(String.class, new SanitizingStringDeserializer()));
    }
}
