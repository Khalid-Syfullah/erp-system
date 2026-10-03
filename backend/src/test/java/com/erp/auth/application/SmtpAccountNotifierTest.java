package com.erp.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpAccountNotifierTest {

    /** Captures messages instead of talking to a server; optionally fails like an unreachable relay. */
    static final class CapturingSender extends JavaMailSenderImpl {
        final BlockingQueue<SimpleMailMessage> sent = new LinkedBlockingQueue<>();
        final boolean fail;

        CapturingSender(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void send(SimpleMailMessage... messages) {
            if (fail) {
                throw new MailSendException("relay unavailable");
            }
            sent.addAll(java.util.List.of(messages));
        }
    }

    private final CapturingSender sender = new CapturingSender(false);
    private final SmtpAccountNotifier notifier = new SmtpAccountNotifier(sender, "no-reply@erp.test");

    @Test
    void invitationContainsTheOneTimeLinkAndItsExpiryInUtc() throws Exception {
        OffsetDateTime expires = OffsetDateTime.of(2026, 10, 6, 12, 30, 0, 0, ZoneOffset.ofHours(2));

        notifier.sendInvitation("ann@example.test", "Ann", "https://erp.test/invite#token=abc", expires);

        SimpleMailMessage message = next();
        assertThat(message.getFrom()).isEqualTo("no-reply@erp.test");
        assertThat(message.getTo()).containsExactly("ann@example.test");
        assertThat(message.getSubject()).isEqualTo("You have been invited to the ERP");
        assertThat(message.getText())
                .contains("Hello Ann", "https://erp.test/invite#token=abc", "2026-10-06 10:30 UTC");
    }

    @Test
    void passwordResetAndNotifications() throws Exception {
        notifier.sendPasswordReset(
                "bo@example.test", "Bo", "https://erp.test/reset-password#token=xyz", OffsetDateTime.now());
        assertThat(next().getText()).contains("https://erp.test/reset-password#token=xyz", "ignore this email");

        notifier.sendPasswordChanged("bo@example.test", "Bo");
        assertThat(next().getSubject()).isEqualTo("Your ERP password was changed");

        notifier.sendAccountLocked("bo@example.test", "Bo", false);
        assertThat(next().getText()).contains("unlocks automatically");
        notifier.sendAccountLocked("bo@example.test", "Bo", true);
        assertThat(next().getText()).contains("An administrator must unlock it");
    }

    @Test
    void deliveryFailuresNeverReachTheCaller() {
        SmtpAccountNotifier failing = new SmtpAccountNotifier(new CapturingSender(true), "no-reply@erp.test");

        failing.sendPasswordChanged("cy@example.test", "Cy");
        // No exception: sending is asynchronous and failures are only logged (without the content).
    }

    private SimpleMailMessage next() throws InterruptedException {
        SimpleMailMessage message = sender.sent.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("message sent").isNotNull();
        return message;
    }
}
