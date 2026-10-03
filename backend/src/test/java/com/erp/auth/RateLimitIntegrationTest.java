package com.erp.auth;

import static com.erp.support.AuthTestSupport.unsafe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AuthTestSupport;
import com.erp.support.AuthTestSupport.TestUser;
import com.erp.support.IntegrationTest;
import com.erp.support.RecordingAccountNotifier;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Brute-force and abuse limits (SECURITY.md §3.4, §9) with tight budgets. */
@TestPropertySource(
        properties = {
            "erp.security.rate-limits.enabled=true",
            "erp.security.rate-limits.session-per-minute=3",
            "erp.security.rate-limits.anonymous-per-minute=100000",
            "erp.auth.login.per-account-per-minute=3",
            "erp.auth.login.per-ip-per-minute=5",
            "erp.auth.tokens.reset-requests-per-email-per-hour=2",
        })
class RateLimitIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    RecordingAccountNotifier mail;

    @Test
    void authenticatedRequestsBeyondTheBudgetGet429WithRetryAfter() throws Exception {
        Cookie session = auth.login(auth.user(), uniqueIp());

        // Fixed one-minute windows: ten requests span at most two windows, i.e. at most six succeed.
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responses.add(
                    mvc.perform(get("/api/v1/me").cookie(session)).andReturn().getResponse());
        }

        assertThat(responses.stream().filter(r -> r.getStatus() == 200)).hasSizeLessThanOrEqualTo(6);
        MockHttpServletResponse limited =
                responses.stream().filter(r -> r.getStatus() == 429).findFirst().orElseThrow();
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
        assertThat(limited.getHeader("RateLimit-Remaining")).isEqualTo("0");
        assertThat(limited.getContentAsString()).contains("\"RATE_LIMITED\"");
    }

    @Test
    void loginAttemptsPerAccountAreLimitedBeforeTheLockout() throws Exception {
        TestUser user = auth.user();

        for (int i = 0; i < 3; i++) {
            login(user.email(), "Wrong-Password-Attempt-" + i, uniqueIp()).andExpect(status().isUnauthorized());
        }

        // From a fresh address and with the correct password: still limited for this account.
        login(user.email(), user.password(), uniqueIp())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void loginAttemptsPerAddressAreLimitedAcrossAccounts() throws Exception {
        String ip = uniqueIp();

        for (int i = 0; i < 5; i++) {
            login("nobody-" + UUID.randomUUID() + "@example.test", "Whatever-Password-123", ip)
                    .andExpect(status().isUnauthorized());
        }

        TestUser user = auth.user();
        login(user.email(), user.password(), ip).andExpect(status().isTooManyRequests());
        login(user.email(), user.password(), uniqueIp()).andExpect(status().isOk());
    }

    @Test
    void passwordResetRequestsAreLimitedSilently() throws Exception {
        TestUser user = auth.user();

        for (int i = 0; i < 4; i++) {
            mvc.perform(unsafe(post("/api/v1/auth/password/forgot"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + user.email() + "\"}"))
                    .andExpect(status().isAccepted());
        }

        // Same answer every time (no enumeration), but only two emails.
        assertThat(mail.to(user.email()).stream().filter(s -> s.kind().equals("PASSWORD_RESET")))
                .hasSize(2);
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mvc.perform(unsafe(post("/api/v1/auth/login"))
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    /** Login attempts are stored per address; other tests share the database and 127.0.0.1. */
    private static String uniqueIp() {
        int n = (int) (Math.random() * 65_000);
        return "10.77." + (n / 256) + "." + (n % 256);
    }
}
