package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MercadoPagoClientTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test void mercadoPagoOrdersCannotReceiveAFakeCompletedRefund() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var simulated = org.mockito.Mockito.mock(com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway.class);
        org.mockito.Mockito.when(jdbc.queryForObject("SELECT transaction_id FROM orders WHERE id=?", String.class, 1L))
                .thenReturn("MP-456");
        var guard = new MercadoPagoRefundGuard(jdbc, simulated);
        assertThatThrownBy(() -> guard.refund("refund-key", 1L, BigDecimal.TEN))
                .isInstanceOf(com.transformersas.marketplace.payments.domain.model.RefundRequestFailedException.class);
        org.mockito.Mockito.verifyNoInteractions(simulated);
    }
    private MercadoPagoClient client(RestClient rest) {
        return new MercadoPagoClient(rest, "test-token", "test-secret", "123",
                "https://shop.example/", "https://shop.example/api/payments/mercadopago/webhook");
    }

    @Test void sendsServerTotalReferenceAndSecretOnlyInAuthorization() {
        var builder = RestClient.builder().baseUrl("https://api.mercadopago.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(jsonPath("$.items[0].unit_price").value(105000))
                .andExpect(jsonPath("$.items[0].currency_id").value("COP"))
                .andExpect(jsonPath("$.external_reference").value("reference"))
                .andExpect(jsonPath("$.back_urls.success").value("https://shop.example/?checkout=reference"))
                .andRespond(withSuccess("""
                        {"id":"pref-1","sandbox_init_point":"https://sandbox.mercadopago.com.co/checkout/v1/redirect?pref_id=pref-1"}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client(builder.build()).create("reference", new BigDecimal("105000"),
                "2026-10-27T12:00:00-05:00").id()).isEqualTo("pref-1");
        server.verify();
    }

    @Test void rejectsProductionOrUntrustedRedirects() {
        for (String host : new String[]{"www.mercadopago.com.co", "sandbox.mercadopago.com.co.evil.example"}) {
            var builder = RestClient.builder().baseUrl("https://api.mercadopago.com");
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(anything()).andRespond(withSuccess("{\"id\":\"p\",\"sandbox_init_point\":\"https://"
                    + host + "/\"}", MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client(builder.build()).create("r", BigDecimal.TEN, "date"))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test void disabledConfigurationCannotFallBackToApprovedPayment() {
        var client = new MercadoPagoClient(RestClient.create(), "", "", "", "", "");
        assertThatThrownBy(client::requireConfigured).isInstanceOf(ResponseStatusException.class);
    }

    @Test void validatesReferenceMerchantCurrencyAmountAndSandboxMode() {
        var client = client(RestClient.create());
        String valid = """
                {"external_reference":"r","collector_id":123,"currency_id":"COP","transaction_amount":105000,"live_mode":false}
                """;
        assertThat(client.matches(json.readTree(valid), "r", new BigDecimal("105000.00"))).isTrue();
        for (String invalid : new String[]{valid.replace("105000", "1"), valid.replace("COP", "USD"),
                valid.replace("123", "999"), valid.replace("\"r\"", "\"other\"")})
            assertThat(client.matches(json.readTree(invalid), "r", new BigDecimal("105000"))).isFalse();
    }

    @Test void liveModeRequiresProviderConfirmedTestCollector() {
        for (String account : new String[]{"{\"id\":123,\"tags\":[\"test_user\"]}",
                "{\"id\":123,\"tags\":[\"normal\"]}", "{\"id\":999,\"tags\":[\"test_user\"]}",
                "{\"id\":123}"}) {
            var builder = RestClient.builder().baseUrl("https://api.mercadopago.com");
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.mercadopago.com/users/me"))
                    .andExpect(header("Authorization", "Bearer test-token"))
                    .andRespond(withSuccess(account, MediaType.APPLICATION_JSON));
            var payment = json.readTree("""
                    {"external_reference":"r","collector_id":123,"currency_id":"COP","transaction_amount":105000,"live_mode":true}
                    """);
            boolean expected = account.equals("{\"id\":123,\"tags\":[\"test_user\"]}");
            assertThat(client(builder.build()).matches(payment, "r", new BigDecimal("105000"))).isEqualTo(expected);
            server.verify();
        }
    }

    @Test void liveModeFailsClosedWhenTestAccountCannotBeVerified() {
        var builder = RestClient.builder().baseUrl("https://api.mercadopago.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withServerError());
        var payment = json.readTree("""
                {"external_reference":"r","collector_id":123,"currency_id":"COP","transaction_amount":105000,"live_mode":true}
                """);
        assertThatThrownBy(() -> client(builder.build()).matches(payment, "r", new BigDecimal("105000")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void verifiesOfficialHmacManifestAndRejectsTampering() throws Exception {
        String id = "1234", request = "request-123", timestamp = "1742505638683";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String digest = HexFormat.of().formatHex(mac.doFinal(("id:" + id + ";request-id:" + request + ";ts:" + timestamp + ";")
                .getBytes(StandardCharsets.UTF_8)));
        String signature = "ts=" + timestamp + ",v1=" + digest;
        assertThatCode(() -> MercadoPagoController.verify("secret", id, request, signature)).doesNotThrowAnyException();
        assertThatThrownBy(() -> MercadoPagoController.verify("secret", "999", request, signature))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> MercadoPagoController.verify("secret", id, request, null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
