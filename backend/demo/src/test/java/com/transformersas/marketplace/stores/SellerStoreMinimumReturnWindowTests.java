package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El mínimo que expone la consulta es el configurado (stores.policy.min-return-window-days), no una constante. */
@TestPropertySource(properties = "stores.policy.min-return-window-days=45")
class SellerStoreMinimumReturnWindowTests extends AbstractIntegrationTest {

    private Session seller;

    @BeforeEach
    void setUp() throws Exception {
        seller = sellerOfStore("seller@example.com", 1);
    }

    private Map<String, Object> settings(int returnWindowDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Tienda principal");
        body.put("returnWindowDays", returnWindowDays);
        body.put("shippingMethods", List.of("STANDARD"));
        body.put("version", 0);
        return body;
    }

    @Test
    void theReadExposesTheConfiguredMinimum() throws Exception {
        perform(seller, get("/api/seller/store")).andExpect(status().isOk())
                .andExpect(jsonPath("$.minReturnWindowDays").value(45));
    }

    @Test
    void theExposedMinimumIsTheOneSavingEnforces() throws Exception {
        perform(seller, put("/api/seller/store").contentType("application/json")
                .content(json.writeValueAsString(settings(44)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_POLICY_BELOW_MINIMUM"))
                .andExpect(jsonPath("$.details.minReturnWindowDays").value(45));

        perform(seller, put("/api/seller/store").contentType("application/json")
                .content(json.writeValueAsString(settings(45)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.returnWindowDays").value(45))
                .andExpect(jsonPath("$.minReturnWindowDays").value(45));
    }
}
