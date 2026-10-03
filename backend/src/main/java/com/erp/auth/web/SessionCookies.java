package com.erp.auth.web;

import com.erp.platform.config.ErpProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Authentication cookies (SECURITY.md §3.3): the session cookie {@code __Host-erp_session} (HttpOnly,
 * Secure, SameSite=Lax, Path=/, no Domain, no Max-Age) and the short-lived MFA challenge cookie
 * {@code __Host-erp_mfa} (SameSite=Strict, 5 minutes). Without secure cookies (plain-HTTP local
 * development only) the {@code __Host-} prefix, which requires {@code Secure}, is dropped.
 */
@Component
public class SessionCookies {

    private final boolean secure;
    private final String sessionCookie;
    private final String challengeCookie;

    public SessionCookies(ErpProperties properties) {
        this.secure = properties.security().secureCookies();
        String prefix = secure ? "__Host-" : "";
        this.sessionCookie = prefix + "erp_session";
        this.challengeCookie = prefix + "erp_mfa";
    }

    public String sessionCookieName() {
        return sessionCookie;
    }

    public String challengeCookieName() {
        return challengeCookie;
    }

    public void setSession(HttpServletResponse response, String token) {
        add(response, sessionCookie, token, "Lax", null);
    }

    public void clearSession(HttpServletResponse response) {
        add(response, sessionCookie, "", "Lax", Duration.ZERO);
    }

    public void setChallenge(HttpServletResponse response, String token, Duration maxAge) {
        add(response, challengeCookie, token, "Strict", maxAge);
    }

    public void clearChallenge(HttpServletResponse response) {
        add(response, challengeCookie, "", "Strict", Duration.ZERO);
    }

    public @Nullable String readSession(HttpServletRequest request) {
        return read(request, sessionCookie);
    }

    public @Nullable String readChallenge(HttpServletRequest request) {
        return read(request, challengeCookie);
    }

    private void add(
            HttpServletResponse response, String name, String value, String sameSite, @Nullable Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/");
        if (maxAge != null) {
            cookie.maxAge(maxAge);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }

    private static @Nullable String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
