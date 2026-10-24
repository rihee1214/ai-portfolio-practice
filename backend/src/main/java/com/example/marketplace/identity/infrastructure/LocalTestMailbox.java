package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.SignupException;
import com.example.marketplace.identity.domain.VerificationMailbox;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Explicit local tool, never an HTTP endpoint or a general application log. */
public final class LocalTestMailbox implements VerificationMailbox {
    private final Clock clock;
    private final List<Message> messages = new ArrayList<>();

    public LocalTestMailbox(Clock clock) {
        this.clock = clock;
    }

    public static final class Message {
        private final String recipient;
        private final String token;
        private final Instant expiresAt;
        private volatile boolean published;

        private Message(String recipient, String token, Instant expiresAt) {
            this.recipient = recipient;
            this.token = token;
            this.expiresAt = expiresAt;
        }

        public String recipient() { return recipient; }
        public String token() { return token; }
        public Instant expiresAt() { return expiresAt; }

        @Override
        public String toString() { return "LocalVerificationMessage[REDACTED]"; }
    }

    @Override
    public synchronized Prepared prepare(String recipient, String token, Instant expiresAt) {
        removeExpired();
        if (messages.size() >= 1000) {
            throw new SignupException("LOCAL_MAILBOX_FULL");
        }
        var message = new Message(recipient, token, expiresAt);
        messages.add(message);
        return new Prepared() {
            @Override
            public void publish() { message.published = true; }

            @Override
            public void discard() {
                synchronized (LocalTestMailbox.this) { messages.remove(message); }
            }
        };
    }

    public synchronized List<Message> messages() {
        removeExpired();
        return messages.stream().filter(message -> message.published).toList();
    }

    private void removeExpired() {
        messages.removeIf(message -> !message.expiresAt.isAfter(clock.instant()));
    }
}
