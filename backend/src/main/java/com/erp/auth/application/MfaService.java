package com.erp.auth.application;

import com.erp.auth.domain.SecureTokens;
import com.erp.auth.domain.Totp;
import com.erp.auth.domain.UserType;
import com.erp.auth.persistence.AssignmentRepository;
import com.erp.auth.persistence.MfaRepository;
import com.erp.auth.persistence.SessionRepository;
import com.erp.auth.persistence.UserRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.crypto.FieldEncryptor;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TOTP multi-factor authentication (SECURITY.md §3.5): enrollment with confirmation, verification
 * with replay protection, ten single-use recovery codes, and the "MFA required" rule (system admins,
 * roles flagged {@code requires_mfa} and roles holding sensitive permissions).
 */
@Service
public class MfaService {

    static final int RECOVERY_CODES = 10;

    /** Data an authenticator app needs; shown once during enrollment. */
    public record Enrollment(String secret, String provisioningUri) {}

    private final MfaRepository mfa;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final AssignmentRepository assignments;
    private final FieldEncryptor encryptor;
    private final AuditPort audit;
    private final AuthProperties properties;
    private final Clock clock;

    public MfaService(
            MfaRepository mfa,
            UserRepository users,
            SessionRepository sessions,
            AssignmentRepository assignments,
            FieldEncryptor encryptor,
            AuditPort audit,
            AuthProperties properties,
            Clock clock) {
        this.mfa = mfa;
        this.users = users;
        this.sessions = sessions;
        this.assignments = assignments;
        this.encryptor = encryptor;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether the user must have a second factor before using the application. */
    @Transactional(readOnly = true)
    public boolean isRequired(AuthUser user) {
        return user.userType() == UserType.HUMAN
                && (user.systemAdmin()
                        || assignments.requiresMfa(user.id(), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    @Transactional
    public Enrollment startEnrollment(UUID userId) {
        AuthUser user = users.lock(userId).orElseThrow(ApiException::notFound);
        if (user.mfaEnabled()) {
            throw new ApiException(
                    AuthErrorCode.MFA_MANDATORY,
                    "An authenticator is already enrolled. Remove it first or ask an administrator to reset it.");
        }
        byte[] secret = SecureTokens.randomBytes(Totp.SECRET_BYTES);
        mfa.savePending(userId, encryptor.encrypt(secret, associatedData(userId)), encryptor.activeKeyVersion(), now());
        return new Enrollment(
                com.erp.auth.domain.Base32.encode(secret),
                Totp.provisioningUri(properties.mfa().issuer(), user.email(), secret));
    }

    /** Confirms enrollment with a first code; returns the recovery codes (shown once). */
    @Transactional
    public List<String> confirmEnrollment(UUID userId, String code) {
        AuthUser user = users.lock(userId).orElseThrow(ApiException::notFound);
        MfaRepository.TotpState state = mfa.lockTotp(userId)
                .filter(s -> s.confirmedAt() == null && !user.mfaEnabled())
                .orElseThrow(() -> new ApiException(
                        AuthErrorCode.MFA_NOT_ENROLLED, "Start the enrollment first (POST /me/mfa/totp/setup)."));
        OptionalLong step = Totp.verify(decrypt(userId, state), code, clock.instant(), null);
        if (step.isEmpty()) {
            throw ApiException.validationFailed(
                    "The verification code is not valid.",
                    List.of(FieldViolation.atPointer(
                            "/code", "INVALID_CODE", "is not the current authenticator code")));
        }
        OffsetDateTime now = now();
        mfa.confirm(userId, step.getAsLong(), now);
        users.setMfaEnabled(userId, true, userId, now);
        sessions.clearMfaEnrollmentRequirement(userId);
        List<String> codes = issueRecoveryCodes(userId, now);
        audit.record(AuditEvent.builder("MFA_CHANGE", "auth")
                .entity("user", userId, user.email())
                .detail("totp", "ENROLLED")
                .build());
        return codes;
    }

    /** Checks a TOTP code for a user with confirmed MFA and consumes its time step. */
    @Transactional
    public boolean verifyCode(UUID userId, String code) {
        return mfa.lockTotp(userId)
                .filter(s -> s.confirmedAt() != null)
                .map(state -> {
                    OptionalLong step =
                            Totp.verify(decrypt(userId, state), code, clock.instant(), state.lastUsedStep());
                    step.ifPresent(s -> mfa.recordUsedStep(userId, s));
                    return step.isPresent();
                })
                .orElse(false);
    }

    @Transactional
    public boolean useRecoveryCode(UUID userId, String recoveryCode) {
        return mfa.consumeRecoveryCode(
                userId, SecureTokens.sha256(SecureTokens.normalizeRecoveryCode(recoveryCode)), now());
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(UUID userId) {
        AuthUser user = users.findById(userId).orElseThrow(ApiException::notFound);
        if (!user.mfaEnabled()) {
            throw new ApiException(AuthErrorCode.MFA_NOT_ENROLLED, "No authenticator is enrolled.");
        }
        List<String> codes = issueRecoveryCodes(userId, now());
        audit.record(AuditEvent.builder("MFA_CHANGE", "auth")
                .entity("user", userId, user.email())
                .detail("recoveryCodes", "REGENERATED")
                .build());
        return codes;
    }

    /** Removes the user's own authenticator; refused while MFA is mandatory for them. */
    @Transactional
    public void disable(UUID userId) {
        AuthUser user = users.lock(userId).orElseThrow(ApiException::notFound);
        if (!user.mfaEnabled()) {
            throw new ApiException(AuthErrorCode.MFA_NOT_ENROLLED, "No authenticator is enrolled.");
        }
        if (isRequired(user)) {
            throw new ApiException(
                    AuthErrorCode.MFA_MANDATORY, "Multi-factor authentication is mandatory for your roles.");
        }
        remove(user);
    }

    /** Administrator reset: removes the authenticator and signs the user out everywhere. */
    @Transactional
    public void reset(AuthUser user) {
        remove(user);
        sessions.deleteForUser(user.id());
    }

    private void remove(AuthUser user) {
        mfa.deleteAll(user.id());
        users.setMfaEnabled(user.id(), false, null, now());
        audit.record(AuditEvent.builder("MFA_CHANGE", "auth")
                .entity("user", user.id(), user.email())
                .detail("totp", "REMOVED")
                .build());
    }

    private List<String> issueRecoveryCodes(UUID userId, OffsetDateTime now) {
        List<String> codes = new ArrayList<>(RECOVERY_CODES);
        List<byte[]> hashes = new ArrayList<>(RECOVERY_CODES);
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = SecureTokens.recoveryCode();
            codes.add(code);
            hashes.add(SecureTokens.sha256(SecureTokens.normalizeRecoveryCode(code)));
        }
        mfa.replaceRecoveryCodes(userId, hashes, now);
        return codes;
    }

    private byte[] decrypt(UUID userId, MfaRepository.TotpState state) {
        return encryptor.decrypt(state.secretEncrypted(), associatedData(userId));
    }

    static String associatedData(UUID userId) {
        return "auth.mfa_totp.secret:" + userId;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
