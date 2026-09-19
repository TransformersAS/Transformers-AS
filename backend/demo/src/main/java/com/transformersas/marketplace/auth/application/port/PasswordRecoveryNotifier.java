package com.transformersas.marketplace.auth.application.port;

/** Delivery boundary. Implementations must not log or persist the plaintext token. */
public interface PasswordRecoveryNotifier {
    void notifyRecovery(String email, String token);
}
