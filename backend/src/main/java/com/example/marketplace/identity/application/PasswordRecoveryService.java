package com.example.marketplace.identity.application;

import com.example.marketplace.identity.api.PasswordRecoveryOperations;
import com.example.marketplace.identity.api.SignupException;
import com.example.marketplace.identity.domain.PasswordHasher;
import com.example.marketplace.identity.domain.PasswordRecoveryStore;
import com.example.marketplace.identity.domain.SignupRules;
import com.example.marketplace.identity.domain.VerificationMailbox;
import java.time.Clock;
import java.time.Duration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PasswordRecoveryService implements PasswordRecoveryOperations {
    private final PasswordRecoveryStore store;
    private final PasswordHasher hasher;
    private final VerificationMailbox mailbox;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PasswordRecoveryService(PasswordRecoveryStore store, PasswordHasher hasher,
            VerificationMailbox mailbox, Clock clock, PlatformTransactionManager manager) {
        this.store = store; this.hasher = hasher; this.mailbox = mailbox; this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override public void request(String email) {
        final String normalized;
        try { normalized = SignupRules.email(email); }
        catch (IllegalArgumentException invalid) { throw new SignupException("INVALID_RECOVERY_INPUT"); }
        transaction.executeWithoutResult(status -> {
            var member = store.lockEligibleMember(normalized);
            if (member == null) { return; }
            var now = clock.instant();
            var expires = now.plus(Duration.ofMinutes(30));
            String token = TokenSecrets.generate();
            store.replaceToken(member, TokenSecrets.hash(token), now, expires);
            var prepared = mailbox.prepare(normalized, token, expires);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { prepared.publish(); }
                @Override public void afterCompletion(int completion) {
                    if (completion != STATUS_COMMITTED) { prepared.discard(); }
                }
            });
        });
    }

    @Override public void reset(String token, String password) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) { throw new SignupException("INVALID_RESET_TOKEN"); }
        try { SignupRules.password(password); }
        catch (IllegalArgumentException invalid) { throw new SignupException("INVALID_RECOVERY_INPUT"); }
        String hash = hasher.encode(password);
        transaction.executeWithoutResult(status -> {
            if (!store.consume(TokenSecrets.hash(token), hash, clock::instant)) {
                throw new SignupException("INVALID_RESET_TOKEN");
            }
        });
    }
}
