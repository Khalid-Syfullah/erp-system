package com.erp.auth.application;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Plain-text account emails over SMTP (Mailpit locally). Sending runs on a virtual thread so that a
 * slow mail server never holds a request; failures are logged without the message content (which may
 * contain a one-time link).
 */
public class SmtpAccountNotifier implements AccountNotifier {

    private static final Logger log = LoggerFactory.getLogger(SmtpAccountNotifier.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final JavaMailSender mailSender;
    private final String from;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SmtpAccountNotifier(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendInvitation(String email, String displayName, String link, OffsetDateTime expiresAt) {
        send(
                email,
                "You have been invited to the ERP",
                "Hello " + displayName + ",\n\n"
                        + "an account has been created for you. Set your password here:\n\n" + link + "\n\n"
                        + "The link can be used once and expires on "
                        + TIME.format(expiresAt.withOffsetSameInstant(java.time.ZoneOffset.UTC))
                        + ".\nIf you did not expect this invitation, ignore this email.\n");
    }

    @Override
    public void sendPasswordReset(String email, String displayName, String link, OffsetDateTime expiresAt) {
        send(
                email,
                "Reset your ERP password",
                "Hello " + displayName + ",\n\n"
                        + "a password reset was requested for your account. Choose a new password here:\n\n" + link
                        + "\n\n"
                        + "The link can be used once and expires on "
                        + TIME.format(expiresAt.withOffsetSameInstant(java.time.ZoneOffset.UTC))
                        + ".\nIf you did not request this, ignore this email; your password stays unchanged.\n");
    }

    @Override
    public void sendPasswordChanged(String email, String displayName) {
        send(
                email,
                "Your ERP password was changed",
                "Hello " + displayName + ",\n\n"
                        + "the password of your account was just changed and other sessions were signed out.\n"
                        + "If this was not you, contact your administrator immediately.\n");
    }

    @Override
    public void sendAccountLocked(String email, String displayName, boolean untilAdministratorUnlocks) {
        send(
                email,
                "Your ERP account was locked",
                "Hello " + displayName + ",\n\n"
                        + "your account was locked after repeated failed sign-in attempts.\n"
                        + (untilAdministratorUnlocks
                                ? "An administrator must unlock it.\n"
                                : "It unlocks automatically after a short time. You can also reset your password.\n")
                        + "If these attempts were not yours, contact your administrator.\n");
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        executor.execute(() -> {
            try {
                mailSender.send(message);
            } catch (MailException e) {
                log.error(
                        "Account email '{}' could not be sent: {}",
                        subject,
                        e.getClass().getSimpleName());
            }
        });
    }
}
