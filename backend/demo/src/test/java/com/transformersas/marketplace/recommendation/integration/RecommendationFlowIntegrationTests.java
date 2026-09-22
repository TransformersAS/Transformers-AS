package com.transformersas.marketplace.recommendation.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.recommendation.interaction.InteractionService;
import com.transformersas.marketplace.recommendation.interaction.InteractionType;
import com.transformersas.marketplace.recommendation.interaction.dto.InteractionRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real security, repositories, MySQL and Gemini HTTP adapter; only the external provider is WireMock. */
class RecommendationFlowIntegrationTests extends AbstractIntegrationTest {
    static final WireMockServer server = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
    static { server.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("gemini.base-url", server::baseUrl);
        p.add("gemini.api-key", () -> "wiremock-only");
        p.add("gemini.model", () -> "integration-model");
    }
    @Autowired InteractionService interactions;
    Session buyer;
    Long buyerId;
    @BeforeEach void buyer() throws Exception {
        server.resetAll();
        jdbc.update("DELETE FROM user_interactions");
        buyer = sessionWithRole("recommendation@example.test", "COMPRADOR");
        buyerId = accountIdOf("recommendation@example.test");
    }
    @AfterAll static void stop() { server.stop(); }
    void search() throws Exception {
        perform(buyer, post("/api/interactions").contentType("application/json")
                .content("{\"interactionType\":\"SEARCH\",\"searchTerm\":\"café\"}"))
                .andExpect(status().isCreated());
    }
    void ai(String text) {
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(okJson(json.writeValueAsString(Map.of("candidates", List.of(Map.of("content",
                        Map.of("parts", List.of(Map.of("text", text))))))))));
    }
    @Test void emptyCatalogueDoesNotContactProvider() throws Exception {
        perform(buyer, get("/api/recommendations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("NO_PRODUCTS"))
                .andExpect(jsonPath("$.products").isEmpty());
        server.verify(0, anyRequestedFor(anyUrl()));
    }
    @Test void noHistoryUsesAvailableStockRankingWithoutProvider() throws Exception {
        seedProduct(1, "Low", 1, "10");
        seedProduct(1, "High", 20, "20");
        seedProduct(1, "Unavailable", 0, "30");
        perform(buyer, get("/api/recommendations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("GENERAL_NO_HISTORY"))
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[0].name").value("High"));
        server.verify(0, anyRequestedFor(anyUrl()));
    }
    @Test void aiFiltersUnknownIdsDeduplicatesLimitsAndUsesPersistedHistory() throws Exception {
        var ids = new ArrayList<Long>();
        for(int i=0;i<7;i++) ids.add(seedProduct(1,"Candidate "+i,10+i,"25"));
        interactions.registerPurchase(buyerId, ids.getFirst());
        search();
        ai("{\"productIds\":[999999999,"+ids.getFirst()+","+ids.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","))+"]}");
        perform(buyer, get("/api/recommendations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("AI"))
                .andExpect(jsonPath("$.products.length()").value(5))
                .andExpect(jsonPath("$.products[0].id").value(ids.getFirst()));
        server.verify(postRequestedFor(urlPathMatching("/v1beta/models/.*:generateContent"))
                .withHeader("x-goog-api-key",equalTo("wiremock-only"))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text",containing("PURCHASE | producto: Candidate 0"))));
        perform(buyer,get("/api/interactions")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }
    @Test void unknownProviderIdsFallBackToCatalogue() throws Exception {
        seedProduct(1,"Available",10,"25"); search(); ai("[999999999]");
        perform(buyer,get("/api/recommendations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("GENERAL_AI_EMPTY"))
                .andExpect(jsonPath("$.products[0].name").value("Available"));
    }
    @ParameterizedTest @ValueSource(ints={429,500})
    void providerFailurePreservesMarketplaceAvailability(int code) throws Exception {
        seedProduct(1,"Available",10,"25"); search();
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(anyUrl()).willReturn(aResponse().withStatus(code)));
        perform(buyer,get("/api/recommendations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("GENERAL_AI_UNAVAILABLE"));
        server.verify(1,postRequestedFor(anyUrl()));
    }
    @Test void invalidInteractionsCannotCreateHistory() throws Exception {
        for(String body: List.of("{}", "{\"interactionType\":\"SEARCH\"}",
                "{\"interactionType\":\"SEARCH\",\"searchTerm\":\" \"}","{\"interactionType\":\"VIEW\"}"))
            perform(buyer,post("/api/interactions").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        perform(buyer,post("/api/interactions").contentType("application/json")
                .content("{\"interactionType\":\"VIEW\",\"productId\":999999999}")).andExpect(status().isNotFound());
        assertThatThrownBy(() -> interactions.register(new InteractionRequest(null,null,InteractionType.SEARCH,"café")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_interactions",Integer.class)).isZero();
    }
}
