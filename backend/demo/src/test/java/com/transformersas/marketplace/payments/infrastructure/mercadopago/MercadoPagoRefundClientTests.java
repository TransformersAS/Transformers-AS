package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MercadoPagoRefundClientTests {
    private MercadoPagoClient client(RestClient rest) {
        return new MercadoPagoClient(rest, "test-only", "test-secret", "123", "https://shop.example/", "https://shop.example/webhook");
    }
    @Test void fullRefundUsesThePaymentApiAndStableIdempotencyHeaderWithoutSensitiveBody() {
        var builder = RestClient.builder().baseUrl("https://mp.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        String key = UUID.randomUUID().toString();
        server.expect(requestTo("https://mp.invalid/v1/payments/456/refunds")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-only")).andExpect(header("X-Idempotency-Key", key))
                .andExpect(content().json("{}"))
                .andRespond(withSuccess("{\"id\":901,\"payment_id\":456,\"amount\":200000,\"status\":\"approved\"}", MediaType.APPLICATION_JSON));
        assertThat(client(builder.build()).requestFullRefund("456", key).path("id").asString()).isEqualTo("901");
        server.verify();
    }
    @Test void consultsRefundsUsingOnlyGet() {
        var builder = RestClient.builder().baseUrl("https://mp.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://mp.invalid/v1/payments/456/refunds")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":901,\"payment_id\":456,\"amount\":200000,\"status\":\"approved\"}]", MediaType.APPLICATION_JSON));
        assertThat(client(builder.build()).refunds("456")).hasSize(1); server.verify();
    }
    @Test void timeoutIsUnknownRatherThanAClaimThatRefundDidNotHappen() {
        var builder = RestClient.builder().baseUrl("https://mp.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST)).andRespond(request -> { throw new IOException("respuesta perdida"); });
        assertThatThrownBy(() -> client(builder.build()).requestFullRefund("456", UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(502));
        server.verify();
    }
    @Test void definitiveProviderRejectionIsDistinguishedFromAnAmbiguousTimeout() {
        var builder = RestClient.builder().baseUrl("https://mp.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST)).andRespond(withBadRequest());
        assertThatThrownBy(() -> client(builder.build()).requestFullRefund("456", UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(422));
        server.verify();
    }
}
