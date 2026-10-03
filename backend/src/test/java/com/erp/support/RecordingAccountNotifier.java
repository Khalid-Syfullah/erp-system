package com.erp.support;

import com.erp.auth.application.AccountNotifier;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures account emails in integration tests instead of sending them. */
public class RecordingAccountNotifier implements AccountNotifier {

    /** One captured email. */
    public record Sent(String kind, String email, String link) {

        /** The token from a {@code ...#token=<token>} link. */
        public String token() {
            return link.substring(link.indexOf("#token=") + "#token=".length());
        }
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendInvitation(String email, String displayName, String link, OffsetDateTime expiresAt) {
        sent.add(new Sent("INVITATION", email, link));
    }

    @Override
    public void sendPasswordReset(String email, String displayName, String link, OffsetDateTime expiresAt) {
        sent.add(new Sent("PASSWORD_RESET", email, link));
    }

    @Override
    public void sendPasswordChanged(String email, String displayName) {
        sent.add(new Sent("PASSWORD_CHANGED", email, ""));
    }

    @Override
    public void sendAccountLocked(String email, String displayName, boolean untilAdministratorUnlocks) {
        sent.add(new Sent(untilAdministratorUnlocks ? "LOCKED_PERMANENTLY" : "LOCKED_TEMPORARILY", email, ""));
    }

    public List<Sent> to(String email) {
        return sent.stream().filter(s -> s.email().equals(email)).toList();
    }

    public Optional<Sent> last(String email, String kind) {
        List<Sent> matching = sent.stream()
                .filter(s -> s.email().equals(email) && s.kind().equals(kind))
                .toList();
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getLast());
    }
}
