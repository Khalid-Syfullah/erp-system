package com.erp.platform.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applies {@link LogRedactor} to every string value of structured (JSON) log events, including
 * messages, MDC values and stack traces. Registered via {@code logging.structured.json.customizer}.
 */
public class RedactingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogRedactor::redact));
    }
}
