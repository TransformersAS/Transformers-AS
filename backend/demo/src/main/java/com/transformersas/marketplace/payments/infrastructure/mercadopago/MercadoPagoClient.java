package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import com.transformersas.marketplace.shared.http.ExternalRestClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import static org.springframework.http.HttpStatus.*;

/** Preferences API + Payments API. Secrets never leave the backend. Sandbox only. */
@Component
public class MercadoPagoClient {
    private final RestClient client;
    private final String token;
    private final String webhookSecret;
    private final String collectorId;
    private final String returnUrl;
    private final String notificationUrl;
    private volatile Boolean verifiedTestCollector;

    @org.springframework.beans.factory.annotation.Autowired
    public MercadoPagoClient(@Value("${payments.mercadopago.access-token:}") String token,
            @Value("${payments.mercadopago.webhook-secret:}") String webhookSecret,
            @Value("${payments.mercadopago.collector-id:}") String collectorId,
            @Value("${payments.mercadopago.return-url:}") String returnUrl,
            @Value("${payments.mercadopago.notification-url:}") String notificationUrl) {
        this(ExternalRestClients.build(RestClient.builder(), "https://api.mercadopago.com",
                Duration.ofSeconds(8), true, "payments.mercadopago"),
                token, webhookSecret, collectorId, returnUrl, notificationUrl);
    }

    // Package-private seam for HTTP contract tests; production host is fixed.
    MercadoPagoClient(RestClient client, String token, String secret, String collector,
                       String returnUrl, String notificationUrl) {
        this.client = client; this.token = token; this.webhookSecret = secret;
        this.collectorId = collector; this.returnUrl = returnUrl; this.notificationUrl = notificationUrl;
    }

    public void requireConfigured() {
        if (token.isBlank() || webhookSecret.isBlank() || !collectorId.matches("[0-9]+")
                || !publicHttps(returnUrl) || !publicHttps(notificationUrl)) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Mercado Pago aún no está configurado. Configura las credenciales de prueba y las URLs HTTPS del backend.");
        }
    }

    private boolean publicHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null && uri.getQuery() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    public record Preference(String id, String checkoutUrl) { }

    public Preference create(String reference, BigDecimal total, String expiresAt) {
        requireConfigured();
        String back = returnUrl + "?checkout=" + reference;
        var payload = Map.of(
                "items", java.util.List.of(Map.of("id", reference, "title", "Compra Marketplace",
                        "quantity", 1, "currency_id", "COP", "unit_price", total)),
                "external_reference", reference,
                "notification_url", notificationUrl,
                "back_urls", Map.of("success", back, "failure", back, "pending", back),
                "auto_return", "approved", "expires", true, "expiration_date_to", expiresAt);
        try {
            JsonNode result = client.post().uri("/checkout/preferences")
                    .headers(h -> h.setBearerAuth(token)).body(payload).retrieve().body(JsonNode.class);
            String id = result == null ? "" : result.path("id").asString("");
            String url = result == null ? "" : result.path("sandbox_init_point").asString("");
            URI target = URI.create(url);
            if (id.isBlank() || !"https".equals(target.getScheme())
                    || !"sandbox.mercadopago.com.co".equals(target.getHost()) || target.getUserInfo() != null) {
                throw new ResponseStatusException(BAD_GATEWAY, "Mercado Pago no devolvió un checkout de pruebas válido");
            }
            return new Preference(id, url);
        } catch (RestClientException | IllegalArgumentException failure) {
            throw new ResponseStatusException(BAD_GATEWAY, "No fue posible iniciar el pago en Mercado Pago");
        }
    }

    public JsonNode payment(String paymentId) {
        requireConfigured();
        if (paymentId == null || !paymentId.matches("[0-9]{1,30}"))
            throw new ResponseStatusException(BAD_REQUEST, "Identificador de pago inválido");
        try {
            JsonNode payment = client.get().uri("/v1/payments/{id}", paymentId)
                    .headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class);
            if (payment == null || !paymentId.equals(payment.path("id").asString()))
                throw new ResponseStatusException(BAD_GATEWAY, "Respuesta de pago inválida");
            return payment;
        } catch (RestClientException failure) {
            throw new ResponseStatusException(BAD_GATEWAY, "No fue posible verificar el pago con Mercado Pago");
        }
    }

    public boolean matches(JsonNode payment, String reference, BigDecimal total) {
        return reference.equals(payment.path("external_reference").asString())
                && collectorId.equals(payment.path("collector_id").asString())
                && "COP".equals(payment.path("currency_id").asString())
                && payment.path("transaction_amount").isNumber()
                && total.compareTo(payment.path("transaction_amount").decimalValue()) == 0
                && payment.path("live_mode").isBoolean()
                && (!payment.path("live_mode").asBoolean() || isVerifiedTestCollector());
    }

    /** Checkout Pro may return live_mode=true for a transaction between test accounts.
     * Never accept that flag alone: verify the token owner's test_user tag with the provider.
     * Configuration is immutable until restart, so only successful identity lookups are cached.
     */
    private boolean isVerifiedTestCollector() {
        Boolean cached = verifiedTestCollector;
        if (cached != null) return cached;
        try {
            JsonNode account = client.get().uri("/users/me").headers(h -> h.setBearerAuth(token))
                    .retrieve().body(JsonNode.class);
            if (account == null || !collectorId.equals(account.path("id").asString())) return false;
            boolean test = false;
            for (JsonNode tag : account.path("tags")) {
                if ("test_user".equals(tag.asString())) test = true;
            }
            verifiedTestCollector = test;
            return test;
        } catch (RestClientException | IllegalArgumentException unavailable) {
            throw new ResponseStatusException(BAD_GATEWAY, "No fue posible verificar la cuenta de pruebas con Mercado Pago");
        }
    }

    public String webhookSecret() { return webhookSecret; }
}
