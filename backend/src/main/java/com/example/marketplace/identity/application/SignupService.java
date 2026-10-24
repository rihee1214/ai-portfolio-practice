package com.example.marketplace.identity.application;

import com.example.marketplace.identity.api.SignupException;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.domain.IdentityStore;
import com.example.marketplace.identity.domain.PasswordHasher;
import com.example.marketplace.identity.domain.SignupRules;
import com.example.marketplace.identity.domain.VerificationMailbox;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class SignupService implements SignupOperations {
    private final IdentityStore store;
    private final PasswordHasher hasher;
    private final VerificationMailbox mailbox;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SignupService(IdentityStore store, PasswordHasher hasher, VerificationMailbox mailbox,
            Clock clock, PlatformTransactionManager manager) {
        this.store = store;
        this.hasher = hasher;
        this.mailbox = mailbox;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public UUID register(String email, String password) {
        final String normalized;
        try {
            normalized = SignupRules.email(email);
            SignupRules.password(password);
        } catch (IllegalArgumentException invalid) {
            throw new SignupException("INVALID_SIGNUP");
        }
        String hash = hasher.encode(password);
        String token = TokenSecrets.generate();
        UUID id = UUID.randomUUID();
        var now = clock.instant();
        var expires = now.plus(Duration.ofMinutes(30));
        try {
            transaction.executeWithoutResult(status -> {
                store.insert(id, normalized, hash, TokenSecrets.hash(token), now, expires);
                var prepared = mailbox.prepare(normalized, token, expires);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        prepared.publish();
                    }

                    @Override
                    public void afterCompletion(int completion) {
                        if (completion != STATUS_COMMITTED) {
                            prepared.discard();
                        }
                    }
                });
            });
            return id;
        } catch (DuplicateKeyException duplicate) {
            throw new SignupException("EMAIL_ALREADY_REGISTERED");
        } catch (DataAccessException failure) {
            throw new SignupException("SIGNUP_UNAVAILABLE");
        }
    }

    @Override
    public void verifyEmail(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new SignupException("INVALID_EMAIL_TOKEN");
        }
        try {
            transaction.executeWithoutResult(status -> {
                if (!store.consumeAndActivate(TokenSecrets.hash(token), clock::instant)) {
                    throw new SignupException("INVALID_EMAIL_TOKEN");
                }
            });
        } catch (DataAccessException failure) {
            throw new SignupException("VERIFICATION_UNAVAILABLE");
        }
    }
}
