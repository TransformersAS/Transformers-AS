package com.transformersas.marketplace.auth.application.port;

public interface EmailVerificationNotifier {
    /** Delivers the secret to the account's mailbox; failures must throw, never silently succeed. */
    void notifyVerification(String email, String token);
}
