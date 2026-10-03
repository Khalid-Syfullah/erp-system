package com.erp.auth.application;

import com.erp.auth.domain.UserStatus;
import com.erp.auth.domain.UserType;
import com.erp.auth.persistence.UserRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-shot creation of the first system administrator ({@code SPRING_PROFILES_ACTIVE=...,bootstrap-admin}
 * with {@code ERP_BOOTSTRAP_ADMIN_EMAIL} and {@code ERP_BOOTSTRAP_ADMIN_PASSWORD} from the secret
 * manager). Does nothing if an active system administrator already exists. The password must satisfy
 * the policy; MFA enrollment is enforced at the first login.
 */
@Component
@Profile("bootstrap-admin")
class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserRepository users;
    private final Passwords passwords;
    private final AuditPort audit;
    private final AuthProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    BootstrapAdmin(
            UserRepository users,
            Passwords passwords,
            AuditPort audit,
            AuthProperties properties,
            TransactionTemplate tx,
            Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        AuthProperties.Bootstrap bootstrap = properties.bootstrap();
        String email = UserAdministrationService.normalizeEmail(bootstrap.adminEmail());
        String displayName = bootstrap.adminDisplayName() == null
                        || bootstrap.adminDisplayName().isBlank()
                ? "System Administrator"
                : bootstrap.adminDisplayName().strip();
        CurrentContext.runWith(
                RequestContext.forRequest("bootstrap-admin-" + UUID.randomUUID()),
                () -> tx.executeWithoutResult(s -> {
                    if (users.countActiveSystemAdmins() > 0) {
                        log.info("An active system administrator exists; bootstrap skipped");
                        return;
                    }
                    passwords.requireAcceptable(bootstrap.adminPassword(), email, displayName, "/password");
                    UUID id = users.insert(
                            email,
                            displayName,
                            UserType.HUMAN,
                            UserStatus.ACTIVE,
                            passwords.hash(bootstrap.adminPassword()),
                            true,
                            null,
                            OffsetDateTime.now(clock));
                    audit.record(AuditEvent.builder("CREATE", "auth")
                            .entity("user", id, email)
                            .detail("isSystemAdmin", true)
                            .detail("source", "bootstrap-admin")
                            .build());
                    log.info("Created the first system administrator (user {})", id);
                }));
    }
}
