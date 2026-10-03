package com.erp.auth.web;

import com.erp.auth.application.AuthErrorCode;
import com.erp.auth.application.CredentialService;
import com.erp.auth.application.LoginService;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.json.RawText;
import com.erp.platform.security.ActorType;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.security.PublicEndpoint;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.ApiProblem;
import com.erp.platform.web.ErrorCode;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (API.md §17.1). Login is CSRF-protected like every cookie-based unsafe
 * request: the SPA first calls {@code GET /auth/csrf}. Successful logins rotate the CSRF token.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
class AuthController {

    private final LoginService login;
    private final CredentialService credentials;
    private final SessionCookies cookies;
    private final ObjectProvider<CsrfTokenRepository> csrfTokens;
    private final ProblemResponses problems;

    AuthController(
            LoginService login,
            CredentialService credentials,
            SessionCookies cookies,
            ObjectProvider<CsrfTokenRepository> csrfTokens,
            ProblemResponses problems) {
        this.login = login;
        this.credentials = credentials;
        this.cookies = cookies;
        this.csrfTokens = csrfTokens;
        this.problems = problems;
    }

    record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotNull @RawText @Size(max = 1024) String password) {}

    record SecondFactorRequest(
            @RawText @Size(max = 6) @Nullable String code,
            @Size(max = 20) @Nullable String recoveryCode) {}

    record ForgotPasswordRequest(@NotBlank @Size(max = 254) String email) {}

    record ResetPasswordRequest(
            @NotBlank @Size(max = 100) String token,
            @NotNull @RawText @Size(max = 1024) String newPassword) {}

    record AcceptInvitationRequest(
            @NotBlank @Size(max = 100) String token,
            @NotNull @RawText @Size(max = 1024) String password) {}

    record LoginResponse(
            AuthResponses.UserResponse user, boolean mfaEnrollmentRequired, OffsetDateTime sessionExpiresAt) {}

    record CsrfResponse(String headerName, String token) {}

    @PublicEndpoint
    @GetMapping("/csrf")
    CsrfResponse csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PublicEndpoint
    @PostMapping("/login")
    ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        RequestContext context = CurrentContext.require();
        return respond(
                login.login(body.email(), body.password(), context.clientIp(), context.userAgent()), request, response);
    }

    @PublicEndpoint
    @PostMapping("/login/mfa")
    ResponseEntity<?> secondFactor(
            @Valid @RequestBody SecondFactorRequest body, HttpServletRequest request, HttpServletResponse response) {
        if ((body.code() == null) == (body.recoveryCode() == null)) {
            throw ApiException.validationFailed(
                    "Send either a code or a recovery code.",
                    List.of(FieldViolation.atPointer(
                            "", "EXACTLY_ONE", "exactly one of code or recoveryCode is required")));
        }
        RequestContext context = CurrentContext.require();
        String challenge = cookies.readChallenge(request);
        LoginService.Outcome outcome = login.verifySecondFactor(
                challenge, body.code(), body.recoveryCode(), context.clientIp(), context.userAgent());
        return respond(outcome, request, response);
    }

    @AllowedDuringMfaEnrollment
    @AuthenticatedEndpoint
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletResponse response) {
        AuthenticatedActor actor = CurrentContext.requireActor();
        if (actor.type() == ActorType.USER) {
            login.logout(actor.userId(), actor.credentialId());
        }
        cookies.clearSession(response);
        return ResponseEntity.noContent().build();
    }

    @PublicEndpoint
    @PostMapping("/password/forgot")
    ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest body) {
        credentials.requestPasswordReset(body.email(), CurrentContext.require().clientIp());
        return ResponseEntity.accepted().build();
    }

    @PublicEndpoint
    @PostMapping("/password/reset")
    ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
        credentials.resetPassword(
                body.token(), body.newPassword(), CurrentContext.require().clientIp());
        return ResponseEntity.noContent().build();
    }

    @PublicEndpoint
    @PostMapping("/invitations/accept")
    ResponseEntity<Void> acceptInvitation(@Valid @RequestBody AcceptInvitationRequest body) {
        credentials.acceptInvitation(
                body.token(), body.password(), CurrentContext.require().clientIp());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> respond(
            LoginService.Outcome outcome, HttpServletRequest request, HttpServletResponse response) {
        return switch (outcome) {
            case LoginService.Authenticated authenticated -> {
                cookies.clearChallenge(response);
                cookies.setSession(response, authenticated.session().token());
                // New CSRF token after authentication (login CSRF / fixation defence).
                CsrfTokenRepository repository = csrfTokens.getObject();
                repository.saveToken(repository.generateToken(request), request, response);
                yield ResponseEntity.ok(new LoginResponse(
                        AuthResponses.UserResponse.from(authenticated.user()),
                        authenticated.mfaEnrollmentRequired(),
                        authenticated.session().absoluteExpiresAt()));
            }
            case LoginService.MfaChallenge challenge -> {
                cookies.setChallenge(
                        response,
                        challenge.challengeToken(),
                        Duration.between(OffsetDateTime.now(), challenge.expiresAt()));
                yield problem(
                        AuthErrorCode.MFA_REQUIRED,
                        "Enter the code from your authenticator app (POST /api/v1/auth/login/mfa).",
                        request);
            }
            case LoginService.Rejected rejected
            when "RATE_LIMITED".equals(rejected.reason()) ->
                ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "60")
                        .header("Content-Type", ApiProblem.MEDIA_TYPE)
                        .body(problems.problem(
                                PlatformErrorCode.RATE_LIMITED,
                                "Too many sign-in attempts. Retry in a minute.",
                                request,
                                List.of()));
            case LoginService.Rejected rejected ->
                problem(
                        AuthErrorCode.valueOf(rejected.reason()),
                        rejected.reason().equals(AuthErrorCode.INVALID_MFA_CODE.name())
                                ? "The verification code is not valid."
                                : "Invalid email or password.",
                        request);
        };
    }

    private ResponseEntity<ApiProblem> problem(ErrorCode code, String detail, HttpServletRequest request) {
        return problems.entity(problems.problem(code, detail, request, List.of()));
    }
}
