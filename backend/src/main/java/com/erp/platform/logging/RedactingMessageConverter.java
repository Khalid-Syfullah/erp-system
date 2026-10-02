package com.erp.platform.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback {@code %m}/{@code %msg}/{@code %message} replacement for console (pattern) logs. */
public class RedactingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogRedactor.redact(super.convert(event));
    }
}
