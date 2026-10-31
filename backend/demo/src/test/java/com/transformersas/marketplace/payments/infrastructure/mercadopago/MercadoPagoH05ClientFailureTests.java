package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MercadoPagoH05ClientFailureTests {
    @Test void connectionFailureIsControlledAndRetryOnlyReadsTheSamePayment() {
        var builder = RestClient.builder().baseUrl("https://mp.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://mp.invalid/v1/payments/456")).andExpect(method(HttpMethod.GET))
                .andRespond(request -> { throw new IOException("Conexión interrumpida simulada"); });
        server.expect(requestTo("https://mp.invalid/v1/payments/456")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"456\",\"status\":\"approved\"}", MediaType.APPLICATION_JSON));
        var client = new MercadoPagoClient(builder.build(), "test-only", "test-secret", "123",
                "https://shop.example/", "https://shop.example/webhook");
        assertThatThrownBy(() -> client.payment("456")).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(502));
        assertThat(client.payment("456").path("status").asString()).isEqualTo("approved");
        server.verify(); // Solo GET interceptados; no preferencia nueva ni conexión de red real.
    }
}
