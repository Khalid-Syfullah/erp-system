package com.erp.auth.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Auth settings ({@code erp.auth.*}); defaults follow SECURITY.md §3 and §9.
 *
 * @param publicBaseUrl base URL of the web application, used in invitation and reset links
 *     (required in production)
 */
@Validated
@ConfigurationProperties(prefix = "erp.auth")
public record AuthProperties(
        String publicBaseUrl,
        @Valid @NotNull @DefaultValue Session session,
        @Valid @NotNull @DefaultValue Login login,
        @Valid @NotNull @DefaultValue Tokens tokens,
        @Valid @NotNull @DefaultValue Mfa mfa,
        @Valid @NotNull @DefaultValue Bootstrap bootstrap,
        @NotNull @DefaultValue("60s") Duration permissionCacheTtl) {

    /**
     * @param idleTimeout inactivity after which a session ends
     * @param absoluteTimeout maximum session lifetime regardless of activity
     */
    public record Session(
            @NotNull @DefaultValue("30m") Duration idleTimeout,
            @NotNull @DefaultValue("12h") Duration absoluteTimeout,
            @Min(1) @Max(50) @DefaultValue("5") int maxConcurrent) {}

    /**
     * @param maxConsecutiveFailures failed passwords before a temporary lock
     * @param lockoutDuration duration of a temporary lock
     * @param lockoutsBeforeAdminUnlock temporary locks within 24 hours before the account is locked
     *     until an administrator unlocks it
     */
    public record Login(
            @Min(1) @DefaultValue("5") int maxConsecutiveFailures,
            @NotNull @DefaultValue("15m") Duration lockoutDuration,
            @Min(1) @DefaultValue("3") int lockoutsBeforeAdminUnlock,
            @Min(1) @DefaultValue("5") int perAccountPerMinute,
            @Min(1) @DefaultValue("20") int perIpPerMinute,
            @NotNull @DefaultValue("90d") Duration attemptRetention) {}

    public record Tokens(
            @NotNull @DefaultValue("72h") Duration invitationTtl,
            @NotNull @DefaultValue("30m") Duration passwordResetTtl,
            @Min(1) @DefaultValue("3") int resetRequestsPerEmailPerHour,
            @Min(1) @DefaultValue("10") int resetRequestsPerIpPerHour,
            @Min(1) @DefaultValue("10") int redemptionsPerIpPerHour,
            @Min(1) @Max(365) @DefaultValue("90") int apiTokenDefaultDays,
            @Min(1) @Max(365) @DefaultValue("365") int apiTokenMaxDays) {}

    public record Mfa(
            @NotNull @DefaultValue("ERP") String issuer,
            @NotNull @DefaultValue("5m") Duration challengeTtl) {}

    /** One-shot creation of the first system administrator (profile {@code bootstrap-admin}). */
    public record Bootstrap(String adminEmail, String adminPassword, String adminDisplayName) {}
}
