package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/** RF-062 y A7: solo la cuenta dueña de la tienda la opera; el resto recibe 403. */
class SellerStoreAuthorizationTests extends AbstractIntegrationTest {

    private Session sellerOfStoreOne;
    private Session sellerOfStoreTwo;
    private long orderOfStoreTwo;

    @BeforeEach
    void setUp() throws Exception {
        seedStore(2, "Otra tienda");
        sellerOfStoreOne = sellerOfStore("uno@example.com", 1);
        sellerOfStoreTwo = sellerOfStore("dos@example.com", 2);
        orderOfStoreTwo = seedOrder(2, "CONFIRMED", seedProduct(2, "Ajena", 5, "10.00"), 1, "10.00");
    }

    @Test
    void aSellerWithHisOwnStoreCannotOperateAnotherStore() throws Exception {
        performAsSeller(sellerOfStoreOne, 2, get("/api/seller/orders")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_AUTHORIZED"));
        performAsSeller(sellerOfStoreOne, 2, get("/api/seller/orders/" + orderOfStoreTwo))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STORE_NOT_AUTHORIZED"));
        performAsSeller(sellerOfStoreOne, 2, post("/api/seller/orders/" + orderOfStoreTwo + "/start-preparation"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STORE_NOT_AUTHORIZED"));

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderOfStoreTwo))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void anUnknownStoreIdGetsTheSameResponseAsAForeignStore() throws Exception {
        String foreign = performAsSeller(sellerOfStoreOne, 2, get("/api/seller/orders"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        String unknown = performAsSeller(sellerOfStoreOne, 999_999, get("/api/seller/orders"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STORE_NOT_AUTHORIZED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(foreign);
    }

    @Test
    void aStoreWithoutOwnerCannotBeOperatedByAnySeller() throws Exception {
        seedStore(3, "Sin dueña");
        Session withoutStore = sessionWithRole("sintienda@example.com", "VENDEDOR");

        performAsSeller(withoutStore, 3, get("/api/seller/orders")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_AUTHORIZED"));
        performAsSeller(sellerOfStoreOne, 3, get("/api/seller/orders")).andExpect(status().isForbidden());
        perform(withoutStore, get("/api/seller/orders")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("STORE_IDENTITY_MISSING"));
    }

    @Test
    void withoutTheHeaderTheAccountsOwnStoreIsUsed() throws Exception {
        seedOrder(1, "CONFIRMED", seedProduct(1, "Propia", 5, "10.00"), 1, "10.00");

        perform(sellerOfStoreTwo, get("/api/seller/orders")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(orderOfStoreTwo));
        performAsSeller(sellerOfStoreTwo, 2, get("/api/seller/orders")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void aMultiRoleAccountOperatesTheStoreOnlyWhileTheSellerRoleIsActive() throws Exception {
        long account = createAccount("multirol@example.com", "COMPRADOR", "VENDEDOR");
        seedStore(3, "Tienda multirol");
        assignStoreOwner(3, account);
        Session session = login("multirol@example.com"); // con dos roles no hay rol activo hasta elegirlo

        performAsSeller(session, 3, get("/api/seller/orders")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_ROLE_REQUIRED"));

        perform(session, put("/api/auth/active-role").contentType("application/json").content("{\"role\":\"COMPRADOR\"}"))
                .andExpect(status().isOk());
        performAsSeller(session, 3, get("/api/seller/orders")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_ROLE_REQUIRED"));
        perform(session, get("/api/seller/orders")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_ROLE_REQUIRED"));

        perform(session, put("/api/auth/active-role").contentType("application/json").content("{\"role\":\"VENDEDOR\"}"))
                .andExpect(status().isOk());
        performAsSeller(session, 3, get("/api/seller/orders")).andExpect(status().isOk());
    }
}
