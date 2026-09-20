package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.infrastructure.web.controller.WebhookSignatureVerifier;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Autenticación del servicio logístico por firma HMAC-SHA256 del cuerpo (RNF-003). */
class WebhookSignatureVerifierTests {

    private static final byte[] BODY = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
    // Vector de prueba conocido de HMAC-SHA256 con la clave "key".
    private static final String KNOWN = "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier("key");

    private static void expectCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(BusinessException.Kind.UNAUTHENTICATED);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test
    void theSignatureMatchesTheKnownHmacTestVector() {
        assertThat(verifier.signature(BODY)).isEqualTo(KNOWN);
    }

    @Test
    void aValidSignatureIsAccepted() {
        assertThatCode(() -> verifier.verify(BODY, KNOWN)).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(BODY, KNOWN.toUpperCase().replace("SHA256=", "sha256="))).doesNotThrowAnyException();
    }

    @Test
    void aMissingWrongOrMalformedSignatureIsRejected() {
        expectCode(() -> verifier.verify(BODY, null), "WEBHOOK_SIGNATURE_INVALID");
        expectCode(() -> verifier.verify(BODY, ""), "WEBHOOK_SIGNATURE_INVALID");
        expectCode(() -> verifier.verify(BODY, KNOWN.substring("sha256=".length())), "WEBHOOK_SIGNATURE_INVALID");
        expectCode(() -> verifier.verify(BODY, "sha256=xyz"), "WEBHOOK_SIGNATURE_INVALID");
        expectCode(() -> verifier.verify(BODY, "sha256=" + "0".repeat(64)), "WEBHOOK_SIGNATURE_INVALID");
        expectCode(() -> verifier.verify(BODY, KNOWN.substring(0, KNOWN.length() - 2)), "WEBHOOK_SIGNATURE_INVALID");
    }

    @Test
    void aSignatureOfAnotherBodyOrAnotherSecretIsRejected() {
        expectCode(() -> verifier.verify("otro cuerpo".getBytes(StandardCharsets.UTF_8), KNOWN), "WEBHOOK_SIGNATURE_INVALID");
        String other = new WebhookSignatureVerifier("otra-clave").signature(BODY);
        expectCode(() -> verifier.verify(BODY, other), "WEBHOOK_SIGNATURE_INVALID");
    }

    @Test
    void withoutAConfiguredSecretTheWebhookStaysClosed() {
        var unconfigured = new WebhookSignatureVerifier("");

        expectCode(() -> unconfigured.verify(BODY, KNOWN), "WEBHOOK_NOT_CONFIGURED");
        expectCode(() -> unconfigured.verify(BODY, null), "WEBHOOK_NOT_CONFIGURED");
    }
}
