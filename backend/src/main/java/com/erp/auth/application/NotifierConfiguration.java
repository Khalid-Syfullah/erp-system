package com.erp.auth.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
class NotifierConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotifierConfiguration.class);

    /**
     * SMTP when {@code spring.mail.host} is configured (required in production). Without it, emails are
     * dropped with a warning that never includes the link (local runs without Mailpit).
     */
    @Bean
    AccountNotifier accountNotifier(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${erp.auth.mail-from:no-reply@erp.invalid}") String from) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender != null && !mailHost.isBlank()) {
            return new SmtpAccountNotifier(sender, from);
        }
        return new AccountNotifier() {
            @Override
            public void sendInvitation(
                    String email, String displayName, String link, java.time.OffsetDateTime expiresAt) {
                log.warn("No SMTP configured (spring.mail.host); invitation email not sent");
            }

            @Override
            public void sendPasswordReset(
                    String email, String displayName, String link, java.time.OffsetDateTime expiresAt) {
                log.warn("No SMTP configured (spring.mail.host); password reset email not sent");
            }

            @Override
            public void sendPasswordChanged(String email, String displayName) {
                log.warn("No SMTP configured (spring.mail.host); password change notice not sent");
            }

            @Override
            public void sendAccountLocked(String email, String displayName, boolean untilAdministratorUnlocks) {
                log.warn("No SMTP configured (spring.mail.host); lockout notice not sent");
            }
        };
    }
}
