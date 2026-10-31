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

class MercadoPagoSearchClientTests {
    private MercadoPagoClient client(RestClient rest) {
        return new MercadoPagoClient(rest, "test-only", "test-secret", "123", "https://shop.example/", "https://shop.example/webhook");
    }
    @Test void searchesByExactExternalReferenceWithPaginationAndAuthorizationUsingOnlyGet() {
        String reference = UUID.randomUUID().toString();
        var builder = RestClient.builder().baseUrl("https://mp.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andExpect(queryParam("external_reference", reference))
                .andExpect(queryParam("offset", "20")).andExpect(queryParam("limit", "20"))
                .andExpect(queryParam("sort", "id")).andExpect(queryParam("criteria", "asc"))
                .andExpect(header("Authorization", "Bearer test-only"))
                .andRespond(withSuccess("{\"paging\":{\"total\":22},\"results\":[{\"id\":456,\"external_reference\":\"" + reference + "\"}]}", MediaType.APPLICATION_JSON));
        var result = client(builder.build()).searchPayments(reference, 20);
        assertThat(result.ids()).containsExactly("456"); assertThat(result.more()).isTrue();
        server.verify();
    }
    @Test void rejectsResultsForAnotherReference() {
        var builder = RestClient.builder().baseUrl("https://mp.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("{\"paging\":{\"total\":1},\"results\":[{\"id\":456,\"external_reference\":\"foreign\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client(builder.build()).searchPayments(UUID.randomUUID().toString(), 0)).isInstanceOf(ResponseStatusException.class);
        server.verify();
    }
    @Test void emptySearchIsNotInventedAsARejectedOrApprovedPayment() {
        var builder = RestClient.builder().baseUrl("https://mp.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("{\"paging\":{\"total\":0},\"results\":[]}", MediaType.APPLICATION_JSON));
        var result = client(builder.build()).searchPayments(UUID.randomUUID().toString(), 0);
        assertThat(result.ids()).isEmpty(); assertThat(result.more()).isFalse(); server.verify();
    }
    @Test void connectionFailureIsControlled() {
        var builder = RestClient.builder().baseUrl("https://mp.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(request -> { throw new IOException("simulated"); });
        assertThatThrownBy(() -> client(builder.build()).searchPayments(UUID.randomUUID().toString(), 0))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(502));
        server.verify();
    }
}
