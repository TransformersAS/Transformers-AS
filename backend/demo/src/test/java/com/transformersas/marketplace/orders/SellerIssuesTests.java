package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RF-121: novedades de preparación (registrar y resolver). */
class SellerIssuesTests extends AbstractIntegrationTest {

    private Long sellerAccountId;
    private Session seller;
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccountId = createAccount("seller@example.com", "VENDEDOR");
        seller = login("seller@example.com");
        seedStore(2, "Otra tienda");
        product = seedProduct(1, "Lámpara", 7, "100.00");
    }

    private ResultActions register(long storeId, long order, String body) throws Exception {
        return performAsSeller(seller, storeId, post("/api/seller/orders/" + order + "/issues")
                .contentType("application/json").content(body));
    }

    private ResultActions register(long order, String type, String description) throws Exception {
        return register(1, order, "{\"type\":\"" + type + "\",\"description\":\"" + description + "\"}");
    }

    private ResultActions resolve(long storeId, long order, long issue) throws Exception {
        return performAsSeller(seller, storeId, post("/api/seller/orders/" + order + "/issues/" + issue + "/resolve"));
    }

    private long issueId(long order) {
        return jdbc.queryForObject("SELECT MAX(id) FROM order_issues WHERE order_id = ?", Long.class, order);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVENTORY_INCONSISTENCY", "DAMAGED_PRODUCT", "OTHER"})
    void rf121_sellerRegistersAnIssueAssociatedToTheOrderAndItIsAudited(String type) throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");

        register(order, type, "Caja golpeada").andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(order)).andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.reportedBy").value("SELLER"))
                .andExpect(jsonPath("$.description").value("Caja golpeada"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM order_issues");
        assertThat(row).containsEntry("order_id", order).containsEntry("reported_by_id", sellerAccountId)
                .containsEntry("status", "OPEN");
        assertThat(jdbc.queryForMap("SELECT * FROM audit_events WHERE action = 'ORDER_ISSUE_REGISTERED'"))
                .containsEntry("actor_type", "SELLER").containsEntry("actor_id", sellerAccountId)
                .containsEntry("entity_id", String.valueOf(order)).containsEntry("outcome", "SUCCESS");
    }

    @ParameterizedTest(name = "estado {0}")
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "IN_PREPARATION"}, mode = EnumSource.Mode.INCLUDE)
    void rf121_issuesAreAcceptedWhileTheOrderIsConfirmedOrInPreparation(OrderStatus current) throws Exception {
        long order = seedOrder(1, current.name(), product, 1, "100.00");

        register(order, "OTHER", "Nota").andExpect(status().isCreated());
    }

    @ParameterizedTest(name = "estado {0}")
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "IN_PREPARATION"}, mode = EnumSource.Mode.EXCLUDE)
    void rf121_issuesAreRejectedInAnyOtherStatus(OrderStatus current) throws Exception {
        long order = seedOrder(1, current.name(), product, 1, "100.00");

        register(order, "OTHER", "Nota").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_NOT_ALLOWED_IN_STATUS"));

        assertThat(count("order_issues")).isZero();
    }

    @Test
    void rf121_invalidIssueRequestsAreRejectedWith400() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");

        for (String body : new String[]{"{}", "{\"type\":\"OTHER\"}", "{\"type\":\"OTHER\",\"description\":\"  \"}",
                "{\"description\":\"x\"}", "{\"type\":\"INVENTADO\",\"description\":\"x\"}",
                "{\"type\":\"OTHER\",\"description\":\"" + "x".repeat(1001) + "\"}",
                "{\"type\":\"OTHER\",\"description\":\"x\",\"status\":\"RESOLVED\"}", // propiedad desconocida
                "{\"type\":\"OTHER\",\"description\":\"x\",\"street\":\"otra\"}", "no es json"}) {
            register(1, order, body).andExpect(status().isBadRequest());
        }

        assertThat(count("order_issues")).isZero();
    }

    @Test
    void rf121_anIssueOfAnotherStoreIsNotFound() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");

        register(2, order, "{\"type\":\"OTHER\",\"description\":\"x\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        assertThat(count("order_issues")).isZero();
    }

    @Test
    void rf121_anOpenInventoryInconsistencyIsNotDuplicated() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        register(order, "INVENTORY_INCONSISTENCY", "Faltan unidades").andExpect(status().isCreated());

        register(order, "INVENTORY_INCONSISTENCY", "Otra vez").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_ALREADY_OPEN"))
                .andExpect(jsonPath("$.details.issueId").value(issueId(order)));

        assertThat(count("order_issues")).isEqualTo(1);
        // Los demás tipos sí admiten varias novedades abiertas.
        register(order, "DAMAGED_PRODUCT", "Una").andExpect(status().isCreated());
        register(order, "DAMAGED_PRODUCT", "Otra").andExpect(status().isCreated());
        assertThat(count("order_issues")).isEqualTo(3);
    }

    @Test
    void rf121_sellerResolvesAnIssueOnceAndItIsAudited() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        register(order, "DAMAGED_PRODUCT", "Caja golpeada").andExpect(status().isCreated());
        long issue = issueId(order);

        resolve(1, order, issue).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").exists());
        resolve(1, order, issue).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_ALREADY_RESOLVED"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM order_issues WHERE id = ?", issue);
        assertThat(row).containsEntry("status", "RESOLVED").containsEntry("resolved_by_id", sellerAccountId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'ORDER_ISSUE_RESOLVED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rf121_aResolvedInventoryInconsistencyCanBeRegisteredAgain() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        register(order, "INVENTORY_INCONSISTENCY", "Faltan unidades").andExpect(status().isCreated());
        resolve(1, order, issueId(order)).andExpect(status().isOk());

        register(order, "INVENTORY_INCONSISTENCY", "Volvió a fallar").andExpect(status().isCreated());

        assertThat(count("order_issues")).isEqualTo(2);
    }

    @Test
    void rf121_resolvingRespectsStoreOrderAndStatusBoundaries() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        long other = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        register(order, "OTHER", "x").andExpect(status().isCreated());
        long issue = issueId(order);

        resolve(2, order, issue).andExpect(status().isNotFound()); // otra tienda
        resolve(1, other, issue).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ISSUE_NOT_FOUND"));
        resolve(1, order, 999_999).andExpect(status().isNotFound());
        jdbc.update("UPDATE orders SET status = 'READY_FOR_DISPATCH' WHERE id = ?", order);
        resolve(1, order, issue).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_NOT_ALLOWED_IN_STATUS"));

        assertThat(jdbc.queryForObject("SELECT status FROM order_issues WHERE id = ?", String.class, issue)).isEqualTo("OPEN");
    }

    @Test
    void rf121_issueListingInTheDetailShowsOnlyOpenIssues() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        register(order, "OTHER", "Abierta").andExpect(status().isCreated());
        register(order, "DAMAGED_PRODUCT", "Se resuelve").andExpect(status().isCreated());
        resolve(1, order, issueId(order)).andExpect(status().isOk());

        performAsSeller(seller, 1, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/seller/orders/" + order)).andExpect(status().isOk())
                .andExpect(jsonPath("$.openIssues", hasSize(1))).andExpect(jsonPath("$.openIssues[0].type").value("OTHER"));
    }
}
