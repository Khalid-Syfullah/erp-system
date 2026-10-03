package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.erp.platform.web.ApiException;
import com.erp.platform.web.PlatformErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecurityPrimitivesTest {

    @Test
    void rateLimiterAllowsBudgetPerKeyAndWindow() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-03T10:00:30Z"), ZoneOffset.UTC);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a", 3).allowed()).isTrue();
        }
        FixedWindowRateLimiter.Decision denied = limiter.tryAcquire("a", 3);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.secondsUntilReset()).isEqualTo(30);
        assertThat(limiter.tryAcquire("b", 3).allowed()).isTrue();

        FixedWindowRateLimiter nextMinute =
                new FixedWindowRateLimiter(Clock.fixed(Instant.parse("2026-10-03T10:01:00Z"), ZoneOffset.UTC));
        assertThat(nextMinute.tryAcquire("a", 3).allowed()).isTrue();
    }

    @Test
    void extractsOriginFromReferer() {
        assertThat(OriginVerificationFilter.originOf("https://erp.example.test:8443/app/page?x=1"))
                .isEqualTo("https://erp.example.test:8443");
        assertThat(OriginVerificationFilter.originOf("https://erp.example.test/"))
                .isEqualTo("https://erp.example.test");
        assertThat(OriginVerificationFilter.originOf("not a url")).isNull();
        assertThat(OriginVerificationFilter.normalize("HTTPS://ERP.Example.test/"))
                .isEqualTo("https://erp.example.test");
    }

    @Test
    void stepUpRequiresARecentPasswordFromASession() {
        Instant now = Instant.parse("2026-10-03T10:00:00Z");
        AuthenticatedActor stale = actor(ActorType.USER, now.minusSeconds(600), null);
        AuthenticatedActor stepped = actor(ActorType.USER, now.minusSeconds(600), now.minusSeconds(60));
        AuthenticatedActor token = actor(ActorType.API_TOKEN, now, null);

        assertThat(catchThrowableOfType(ApiException.class, () -> ReauthenticationGuard.require(stale, now))
                        .errorCode())
                .isEqualTo(PlatformErrorCode.REAUTHENTICATION_REQUIRED);
        assertThatCode(() -> ReauthenticationGuard.require(stepped, now)).doesNotThrowAnyException();
        assertThat(catchThrowableOfType(ApiException.class, () -> ReauthenticationGuard.require(token, now))
                        .errorCode())
                .isEqualTo(PlatformErrorCode.REAUTHENTICATION_REQUIRED);
    }

    @Test
    void segregationOfDutiesRejectsTheSameUser() {
        UUID user = UUID.randomUUID();

        assertThat(catchThrowableOfType(
                                ApiException.class,
                                () -> SegregationOfDuties.requireDifferentUsers(user, user, "approve your own order"))
                        .errorCode())
                .isEqualTo(PlatformErrorCode.SOD_VIOLATION);
        assertThatCode(() -> SegregationOfDuties.requireDifferentUsers(user, UUID.randomUUID(), "x"))
                .doesNotThrowAnyException();
    }

    private static AuthenticatedActor actor(ActorType type, Instant authenticatedAt, Instant reauthenticatedAt) {
        return new AuthenticatedActor(
                UUID.randomUUID(),
                type,
                UUID.randomUUID(),
                false,
                authenticatedAt,
                reauthenticatedAt,
                null,
                null,
                false,
                null);
    }
}
