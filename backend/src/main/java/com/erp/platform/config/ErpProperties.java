package com.erp.platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Typed application configuration under the {@code erp.*} prefix. Values come from
 * {@code application*.yml} defaults and environment variables; secrets are never defaulted.
 */
@Validated
@ConfigurationProperties(prefix = "erp")
public record ErpProperties(
        @Valid @NotNull @DefaultValue Api api,
        @Valid @NotNull @DefaultValue Security security) {

    /**
     * @param cursorSigningKey Base64 HMAC key (at least 32 bytes) signing pagination cursors. Required
     *     in production; when blank elsewhere an ephemeral random key is generated at startup.
     * @param maxRequestBodySize upper bound for JSON request bodies (API.md §7, SECURITY.md §6).
     */
    public record Api(
            String cursorSigningKey,
            @NotNull @DefaultValue("1MB") DataSize maxRequestBodySize) {}

    /**
     * @param csrfCookieSecure whether the CSRF cookie carries the {@code Secure} attribute (only
     *     disable for plain-HTTP local development).
     */
    public record Security(@DefaultValue("true") boolean csrfCookieSecure) {}
}
