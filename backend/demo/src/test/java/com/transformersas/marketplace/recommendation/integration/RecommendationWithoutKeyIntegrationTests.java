package com.transformersas.marketplace.recommendation.integration;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties={"gemini.api-key=", "gemini.base-url=http://127.0.0.1:1"})
class RecommendationWithoutKeyIntegrationTests extends AbstractIntegrationTest {
    @Test void persistedHistoryWithoutApiKeyUsesGeneralRecommendations() throws Exception {
        jdbc.update("DELETE FROM user_interactions");
        var buyer=sessionWithRole("no-key@example.test","COMPRADOR");
        seedProduct(1,"Available",10,"20");
        perform(buyer,post("/api/interactions").contentType("application/json")
                .content("{\"interactionType\":\"SEARCH\",\"searchTerm\":\"ropa\"}"))
                .andExpect(status().isCreated());
        perform(buyer,get("/api/recommendations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("GENERAL_NO_API_KEY"))
                .andExpect(jsonPath("$.products[0].name").value("Available"));
    }
}
