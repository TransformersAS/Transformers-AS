package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H01: filtros ejecutados contra MySQL temporal, sesiones reales y aislamiento obligatorio. */
class BuyerOrderFilterTests extends AbstractIntegrationTest {
    private Session first, second;
    private long firstAccount, secondAccount, product;
    private long early, opening, closing, tie, next, cancelled, foreign;

    @BeforeEach void fixtures() throws Exception {
        first = sessionWithRole("filters-first@example.test", "COMPRADOR");
        second = sessionWithRole("filters-second@example.test", "COMPRADOR");
        firstAccount = accountIdOf("filters-first@example.test");
        secondAccount = accountIdOf("filters-second@example.test");
        product = seedProduct(1, "Producto histórico", 10, "100.00");
        early = order(firstAccount, "CONFIRMED", "2026-09-30 23:59:59.999999");
        opening = order(firstAccount, "CONFIRMED", "2026-10-01 00:00:00.000000");
        closing = order(firstAccount, "CONFIRMED", "2026-10-31 23:59:59.999999");
        tie = order(firstAccount, "CONFIRMED", "2026-10-31 23:59:59.999999");
        next = order(firstAccount, "CONFIRMED", "2026-11-01 00:00:00.000000");
        cancelled = order(firstAccount, "CANCELLED", "2026-10-15 12:00:00.000000");
        foreign = order(secondAccount, "CONFIRMED", "2026-10-31 23:59:59.999999");
        order(null, "CONFIRMED", "2026-10-31 23:59:59.999999");
    }

    private long order(Long account, String status, String createdAt) {
        long id = seedOrder(1, status, product, 1, "100.00");
        jdbc.update("UPDATE orders SET account_id=?, created_at=? WHERE id=?", account, createdAt, id);
        return id;
    }

    private List<Long> ids(Session session, String query) throws Exception {
        var view = json.readTree(perform(session, get("/api/orders" + query)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(view.isArray()).isTrue();
        return view.valueStream().map(row -> row.get("id").asLong()).toList();
    }

    @Test void unfilteredListKeepsContractAndStableDescendingOrder() throws Exception {
        assertThat(ids(first, "")).containsExactly(next, tie, closing, cancelled, opening, early);
        perform(first, get("/api/orders")).andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].total").value(100))
                .andExpect(jsonPath("$[0].shippingMethod").value("STANDARD"))
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test void filtersByOneState() throws Exception {
        assertThat(ids(first, "?status=CANCELLED")).containsExactly(cancelled);
        assertThat(ids(first, "?status=CONFIRMED")).containsExactly(next, tie, closing, opening, early);
    }

    @Test void rangeIncludesBothEntireDaysAtMicrosecondPrecision() throws Exception {
        assertThat(ids(first, "?from=2026-10-01&to=2026-10-31"))
                .containsExactly(tie, closing, cancelled, opening);
        assertThat(ids(first, "?from=2026-10-31&to=2026-10-31")).containsExactly(tie, closing);
    }

    @Test void eitherDateMayBeOmitted() throws Exception {
        assertThat(ids(first, "?from=2026-10-31")).containsExactly(next, tie, closing);
        assertThat(ids(first, "?to=2026-10-01")).containsExactly(opening, early);
    }

    @Test void combinesDatesAndState() throws Exception {
        assertThat(ids(first, "?from=2026-10-01&to=2026-10-31&status=CONFIRMED"))
                .containsExactly(tie, closing, opening);
    }

    @Test void noMatchesReturnsAnEmptyArray() throws Exception {
        assertThat(ids(first, "?status=DELIVERED")).isEmpty();
        assertThat(ids(first, "?from=2027-01-01")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"from,ayer,INVALID_ORDER_DATE", "to,2026-02-30,INVALID_ORDER_DATE",
            "from,2026-13-01,INVALID_ORDER_DATE", "to,2026-1-01,INVALID_ORDER_DATE",
            "from,0000-01-01,INVALID_ORDER_DATE", "to,10000-01-01,INVALID_ORDER_DATE",
            "status,UNKNOWN,INVALID_ORDER_STATUS", "status,confirmed,INVALID_ORDER_STATUS"})
    void invalidParametersHaveControlledErrors(String name, String value, String code) throws Exception {
        perform(first, get("/api/orders").param(name, value)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test void emptyParametersAndInvertedRangeAreRejected() throws Exception {
        for (String name : List.of("from", "to", "status")) {
            perform(first, get("/api/orders").param(name, "")).andExpect(status().isBadRequest());
        }
        perform(first, get("/api/orders?from=2026-10-31&to=2026-10-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test void identicalDatesAndStatesNeverMixAccountsEvenWithSpoofedAccount() throws Exception {
        String query = "?from=2026-10-31&to=2026-10-31&status=CONFIRMED";
        assertThat(ids(first, query + "&accountId=" + secondAccount)).containsExactly(tie, closing);
        assertThat(ids(second, query + "&accountId=" + firstAccount)).containsExactly(foreign);
        perform(first, get("/api/orders" + query).header("X-Account-Id", secondAccount))
                .andExpect(jsonPath("$.length()").value(2));
        perform(second, get("/api/orders/{id}", closing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders" + query)).andExpect(status().isUnauthorized());
        var seller = sessionWithRole("filter-seller@example.test", "VENDEDOR");
        perform(seller, get("/api/orders" + query)).andExpect(status().isForbidden());
        perform(second, head("/api/orders" + query)).andExpect(status().isOk());
    }

    @Test void cancellationChangesFilteredMembershipButKeepsHistoricalDetail() throws Exception {
        jdbc.update("UPDATE orders SET discount_amount=0, shipping_cost=0, seller_name_snapshot='Tienda original' WHERE id=?", opening);
        var before = json.readTree(perform(first, get("/api/orders/{id}", opening)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        perform(first, post("/api/orders/{id}/cancellation", opening).contentType("application/json")
                .content("{\"reasonCode\":\"CHANGED_MIND\"}")).andExpect(status().isOk());
        assertThat(ids(first, "?from=2026-10-01&to=2026-10-01&status=CONFIRMED")).isEmpty();
        assertThat(ids(first, "?from=2026-10-01&to=2026-10-01&status=CANCELLED")).containsExactly(opening);
        var after = json.readTree(perform(first, get("/api/orders/{id}", opening)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andReturn().getResponse().getContentAsString());
        for (String field : List.of("items", "delivery", "sellerNameSnapshot", "discountAmount", "shippingCost", "total")) {
            assertThat(after.get(field)).isEqualTo(before.get(field));
        }
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(11);
        assertThat(count("refunds")).isEqualTo(1);
    }
}
