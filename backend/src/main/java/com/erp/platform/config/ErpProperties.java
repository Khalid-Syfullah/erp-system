package com.erp.platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Typed platform configuration under the {@code erp.*} prefix. Values come from
 * {@code application*.yml} defaults and environment variables; secrets are never defaulted.
 * Module-specific settings live in the modules (e.g. {@code erp.auth.*}).
 */
@Validated
@ConfigurationProperties(prefix = "erp")
public record ErpProperties(
        @Valid @NotNull @DefaultValue Api api,
        @Valid @NotNull @DefaultValue Security security,
        @Valid @NotNull @DefaultValue Crypto crypto) {

    /**
     * @param cursorSigningKey Base64 HMAC key (at least 32 bytes) signing pagination cursors. Required
     *     in production; when blank elsewhere an ephemeral random key is generated at startup.
     * @param maxRequestBodySize upper bound for JSON request bodies (API.md §7, SECURITY.md §6).
     */
    public record Api(
            String cursorSigningKey,
            @NotNull @DefaultValue("1MB") DataSize maxRequestBodySize) {}

    /**
     * @param secureCookies whether cookies carry {@code Secure} (and the session cookie the
     *     {@code __Host-} prefix). Only disable for plain-HTTP local development.
     * @param allowedOrigins exact origins (scheme://host[:port]) allowed to send cookie-authenticated
     *     unsafe requests (SECURITY.md §10.3). Required in production.
     */
    public record Security(
            @DefaultValue("true") boolean secureCookies,
            @NotNull @DefaultValue List<String> allowedOrigins,
            @Valid @NotNull @DefaultValue RateLimits rateLimits) {}

    /**
     * Per-instance request budgets (SECURITY.md §9). The load balancer adds coarse per-IP limits.
     *
     * @param sessionPerMinute requests per minute per browser-session user
     * @param tokenPerMinute default requests per minute per API token (a token may set its own)
     * @param anonymousPerMinute requests per minute per client address without credentials
     */
    public record RateLimits(
            @DefaultValue("true") boolean enabled,
            @Min(1) @Max(1_000_000) @DefaultValue("600") int sessionPerMinute,
            @Min(1) @Max(1_000_000) @DefaultValue("1200") int tokenPerMinute,
            @Min(1) @Max(1_000_000) @DefaultValue("300") int anonymousPerMinute) {}

    /**
     * @param fieldEncryptionKeys versioned AES-256 keys for field-level encryption (SECURITY.md §7.2),
     *     formatted {@code <version>:<base64 32 bytes>[,<version>:<base64>...]}; the highest version
     *     encrypts new values. Required in production; elsewhere an ephemeral key is generated.
     */
    public record Crypto(String fieldEncryptionKeys) {}
}
