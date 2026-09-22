package com.transformersas.marketplace.auth;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RemainingAccessBlockersTests extends AbstractIntegrationTest {
    private static final String PRODUCT = """
            {"name":"Owned","price":10,"stock":2,"category":"Hogar","storeId":1}""";

    @Test
    void productCreationRequiresActiveSellerAndUsesOwnedStore() throws Exception {
        for (String role : new String[]{"COMPRADOR", "SOPORTE", "ADMIN"}) {
            Session forbidden = sessionWithRole(role + "@example.com", role);
            perform(forbidden, post("/api/products").contentType("application/json").content(PRODUCT))
                    .andExpect(status().isForbidden());
        }
        createAccount("multiple@example.com", "COMPRADOR", "VENDEDOR");
        perform(login("multiple@example.com"), post("/api/products").contentType("application/json").content(PRODUCT))
                .andExpect(status().isForbidden());
        long store = seedStore(42L, "Owned store");
        Session seller = sellerOfStore("seller@example.com", store);
        perform(seller, post("/api/products").contentType("application/json").content(PRODUCT))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT store_id FROM products WHERE name='Owned'", Long.class)).isEqualTo(store);
        perform(seller, post("/api/products").header("X-Store-Id", "1").contentType("application/json").content(PRODUCT))
                .andExpect(status().isForbidden());
    }

    @Test
    void activityAndRecommendationsUseSessionIdentityDespiteClientSelectors() throws Exception {
        Session a = sessionWithRole("a@example.com", "COMPRADOR");
        Session b = sessionWithRole("b@example.com", "COMPRADOR");
        long aId = accountIdOf("a@example.com");
        long bId = accountIdOf("b@example.com");
        perform(a, post("/api/interactions").contentType("application/json").content(
                "{\"userId\":" + bId + ",\"interactionType\":\"SEARCH\",\"searchTerm\":\"private A\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.userId").value(aId));
        perform(b, get("/api/interactions").param("userId", String.valueOf(aId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        perform(a, get("/api/interactions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(aId));
        perform(b, get("/api/recommendations").param("userId", String.valueOf(aId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(bId));
        mvc.perform(get("/api/interactions").param("userId", String.valueOf(aId))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/recommendations")).andExpect(status().isUnauthorized());
    }
}
