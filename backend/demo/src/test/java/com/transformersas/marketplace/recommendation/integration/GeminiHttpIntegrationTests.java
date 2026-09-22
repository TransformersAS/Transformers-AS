package com.transformersas.marketplace.recommendation.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.recommendation.infrastructure.GeminiRecommendationClient;
import com.transformersas.marketplace.recommendation.interaction.InteractionType;
import com.transformersas.marketplace.recommendation.interaction.UserInteraction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

/** Spring injects the production HTTP adapter; every request goes to a loopback WireMock server. */
@SpringBootTest(classes = GeminiHttpIntegrationTests.Config.class)
class GeminiHttpIntegrationTests {
    static final WireMockServer server = new WireMockServer(options().dynamicPort().bindAddress("127.0.0.1").http2PlainDisabled(true));
    static { server.start(); }
    static final String PATH = "/v1beta/models/integration-model:generateContent";
    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean GeminiRecommendationClient client() { return new GeminiRecommendationClient("wiremock-only", "integration-model"); }
        @Bean GeminiRecommendationClient missingKeyClient() { return new GeminiRecommendationClient("", "integration-model"); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("gemini.base-url", server::baseUrl);
    }
    @Autowired GeminiRecommendationClient client;
    @Autowired GeminiRecommendationClient missingKeyClient;
    @BeforeEach void reset() { server.resetAll(); }
    @AfterAll static void stop() { server.stop(); }
    void response(String body) { server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(body))); }
    void textResponse(String text) {
        response(new JsonMapper().writeValueAsString(Map.of("candidates", List.of(Map.of("content",
                Map.of("parts", List.of(Map.of("text", text))))))));
    }
    List<Long> call() { return client.recommend(List.of(), List.of(), Map.of()); }

    @Test void requestContractAndHistoryProduceOrderedDistinctIds() {
        Product product = new Product(41L, "Café", "Origen local", new BigDecimal("25000"), 10, "Alimentos", true);
        var now = LocalDateTime.now();
        var history = List.of(new UserInteraction(7L, 41L, InteractionType.PURCHASE, null, now),
                new UserInteraction(7L, null, InteractionType.SEARCH, "café molido", now),
                new UserInteraction(7L, 999L, InteractionType.VIEW, " ", now));
        textResponse("{\"productIds\":[41,42,41,43]}");
        assertThat(client.recommend(List.of(product), history, Map.of(41L, product))).containsExactly(41L,42L,43L);
        server.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("x-goog-api-key", equalTo("wiremock-only"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", containing("PURCHASE | producto: Café | categoría: Alimentos")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", containing("SEARCH | búsqueda: café molido")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text", containing("41 | Café | categoría: Alimentos | descripción: Origen local")))
                .withRequestBody(matchingJsonPath("$.generationConfig.responseFormat.text.mimeType", equalTo("APPLICATION_JSON")))
                .withRequestBody(matchingJsonPath("$.generationConfig.responseFormat.text.schema.properties.productIds.maxItems", equalTo("5")))
                .withRequestBody(matchingJsonPath("$.generationConfig.responseFormat.text.schema.required[0]", equalTo("productIds"))));
    }
    @Test void missingKeyFailsBeforeAnyNetworkRequest() {
        assertThat(missingKeyClient.isConfigured()).isFalse();
        assertThatThrownBy(() -> missingKeyClient.recommend(List.of(), List.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("API key no configurada");
        server.verify(0, anyRequestedFor(anyUrl()));
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "{\"candidates\":[]}"})
    void missingCandidates(String body) {
        response(body);
        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class).hasMessageContaining("no devolvió candidatos");
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"content\":{}}", "{\"content\":{\"parts\":[]}}"})
    void missingContent(String candidate) {
        response("{\"candidates\":["+candidate+"]}");
        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class).hasMessageContaining("no devolvió contenido");
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"text\":\"\"}", "{\"text\":\"  \"}"})
    void missingText(String part) {
        response("{\"candidates\":[{\"content\":{\"parts\":["+part+"]}}]}");
        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class).hasMessageContaining("respuesta vacía");
    }
    @Test void malformedArrayIsRejected() {
        textResponse("{\"productIds\":null}");
        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class).hasMessageContaining("inválida");
    }
    @ParameterizedTest @ValueSource(strings={"[]", "[\"unknown\"]"})
    void arrayWithoutIdsIsRejected(String array) {
        textResponse(array);
        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class).hasMessageContaining("no recomendó productos");
    }
    @ParameterizedTest @ValueSource(ints={400,429,500,503})
    void httpFailuresArePropagatedWithoutRetries(int status) {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)));
        assertThatThrownBy(this::call).isInstanceOf(HttpStatusCodeException.class);
        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }
}
