package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.orders.application.usecase.CreateOrderUseCase;
import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.infrastructure.persistence.mapper.OrderMapper;
import com.transformersas.marketplace.orders.infrastructure.persistence.repository.SpringDataOrderRepository;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class OrderOwnershipIntegrationTests {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("order_ownership_test").withUsername("test").withPassword("test");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired SpringDataOrderRepository orders;
    @Autowired CreateOrderUseCase createOrder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SimulatedRefundGateway refundGateway;
    private Long firstAccount;
    private Long secondAccount;
    private Long address;
    private Long product;
    private Long cart;

    @BeforeEach
    void prepare() {
        refundGateway.reset();
        for (String table : List.of("audit_events", "notifications", "refunds", "order_cancellations", "shipments",
                "order_issues", "order_status_history", "order_items", "orders", "inventory_reservations", "cart_items", "carts",
                "products", "addresses", "SPRING_SESSION", "user_account_roles", "user_accounts")) {
            jdbc.update("DELETE FROM " + table);
        }
        String hash = encoder.encode("OrderPassword!123");
        firstAccount = accounts.save(new UserAccount(null, "first@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR))).id();
        secondAccount = accounts.save(new UserAccount(null, "second@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR))).id();
        jdbc.update("UPDATE user_accounts SET email_verified_at = CURRENT_TIMESTAMP(6)");
        jdbc.update("INSERT INTO addresses(recipient_name,street,city,department,phone) VALUES ('Ana','Calle 1','Bogotá','Bogotá','1234567')");
        address = jdbc.queryForObject("SELECT id FROM addresses", Long.class);
        jdbc.update("INSERT INTO products(name,price,stock,category,active) VALUES ('Producto',100,10,'Hogar',true)");
        product = jdbc.queryForObject("SELECT id FROM products", Long.class);
        jdbc.update("INSERT INTO carts() VALUES ()");
        cart = jdbc.queryForObject("SELECT id FROM carts", Long.class);
    }

    @Test
    void eachAuthenticatedBuyerOwnsTheirNewOrderAndClientCannotChooseOwner() throws Exception {
        Session first = login("first@example.com");
        Long firstOrder = pay(first, "CARD", secondAccount);
        Order saved = load(firstOrder);
        assertThat(saved.accountId()).isEqualTo(firstAccount);
        assertThat(saved.addressId()).isEqualTo(address);
        assertThat(saved.shippingMethod()).isEqualTo("STANDARD");
        assertThat(saved.status().name()).isEqualTo("CONFIRMED");
        assertThat(saved.transactionId()).isNotBlank();
        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.total()).isEqualByComparingTo("10200");
        assertThat(saved.items()).hasSize(1);
        assertThat(saved.items().getFirst().productId()).isEqualTo(product);
        assertThat(saved.items().getFirst().quantity()).isEqualTo(2);
        assertThat(saved.items().getFirst().unitPrice()).isEqualByComparingTo("100");
        assertThat(saved.items().getFirst().subtotal()).isEqualByComparingTo("200");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(8);
        Long secondOrder = pay(login("second@example.com"), "CARD", firstAccount);
        assertThat(load(secondOrder).accountId()).isEqualTo(secondAccount);
        assertThat(load(firstOrder).accountId()).isEqualTo(firstAccount);
    }

    @Test
    void historicalOrdersWithNoOwnerCanBeLoadedAndMapped() {
        jdbc.update("""
                INSERT INTO orders(status,total,store_id,address_id,shipping_method,transaction_id,created_at,
                                   delivery_recipient_name,delivery_street,delivery_city,delivery_department,delivery_phone)
                VALUES ('CONFIRMED',100,1,?,'STANDARD','historical',CURRENT_TIMESTAMP,'Ana','Calle 1','Bogotá','Bogotá','1234567')
                """, address);
        Long id = jdbc.queryForObject("SELECT id FROM orders", Long.class);
        jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES (?,?,'Producto',1,100,100)", id, product);
        Order historical = load(id);
        assertThat(historical.id()).isEqualTo(id);
        assertThat(historical.accountId()).isNull();
        assertThat(historical.items()).hasSize(1);
        assertThat(OrderMapper.newEntity(historical).getAccountId()).isNull();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            var entity = orders.findById(id).orElseThrow();
            var persistedItem = entity.getItems().getFirst();
            assertThat(persistedItem.getId()).isPositive();
            assertThat(persistedItem.getOrder().getId()).isEqualTo(id);
            assertThat(persistedItem.getProductId()).isEqualTo(product);
        });
    }

    @Test
    void newOrdersRequireAnExistingAccount() {
        for (Long accountId : java.util.Arrays.asList(null, 0L, -1L, Long.MAX_VALUE)) {
            assertThatThrownBy(() -> createOrder.execute(accountId, address, "STANDARD", "invalid", BigDecimal.ONE))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void anonymousPaymentIsRejectedBeforeCreatingOrder() throws Exception {
        Session anonymous = csrf(null);
        mvc.perform(post("/api/payments/process").cookie(anonymous.cookie()).header(anonymous.header(), anonymous.token())
                .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEST_REJECT", "TEST_PENDING"})
    void nonApprovedPaymentsStillDoNotCreateOrders(String method) throws Exception {
        assertThat(pay(login("first@example.com"), method, secondAccount)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations", String.class))
                .isEqualTo(method.equals("TEST_REJECT") ? "RELEASED" : "ACTIVE");
    }

    @Test
    void listIncludesOnlyOwnOrdersAndIgnoresClientAccountSelectors() throws Exception {
        Long older = seedOrder(firstAccount, "own-older");
        Long newer = seedOrder(firstAccount, "own-newer");
        seedOrder(secondAccount, "foreign");
        seedOrder(null, "historical");
        Session first = login("first@example.com");
        mvc.perform(get("/api/orders").cookie(first.cookie()).param("accountId", secondAccount.toString())
                        .param("buyerId", secondAccount.toString()).header("X-Account-Id", secondAccount)
                        .contentType("application/json").content("{\"accountId\":" + secondAccount + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer)).andExpect(jsonPath("$[1].id").value(older))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].total").value(200))
                .andExpect(jsonPath("$[0].shippingMethod").value("STANDARD"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-01-01T12:00:00"))
                .andExpect(jsonPath("$[0].items").doesNotExist());
        Session second = login("second@example.com");
        mvc.perform(get("/api/orders").cookie(second.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listIsEmptyWhenAccountHasNoOrders() throws Exception {
        seedOrder(secondAccount, "foreign");
        seedOrder(null, "historical");
        mvc.perform(get("/api/orders").cookie(login("first@example.com").cookie()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void ownOrderDetailMapsAllMainFieldsAndItems() throws Exception {
        Long id = seedOrder(firstAccount, "own-detail");
        Session session = login("first@example.com");
        mvc.perform(get("/api/orders/{id}", id).cookie(session.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.total").value(200))
                .andExpect(jsonPath("$.addressId").value(address))
                .andExpect(jsonPath("$.shippingMethod").value("STANDARD"))
                .andExpect(jsonPath("$.transactionId").value("own-detail"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T12:00:00"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(product))
                .andExpect(jsonPath("$.items[0].productName").value("Producto"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(100))
                .andExpect(jsonPath("$.items[0].subtotal").value(200));
        mvc.perform(head("/api/orders/{id}", id).cookie(session.cookie())).andExpect(status().isOk());
        mvc.perform(head("/api/orders").cookie(session.cookie())).andExpect(status().isOk());
    }

    @Test
    void foreignHistoricalAndMissingOrdersAllReturn404() throws Exception {
        Long foreign = seedOrder(secondAccount, "foreign");
        Long historical = seedOrder(null, "historical");
        Session session = login("first@example.com");
        for (Long id : List.of(foreign, historical, Long.MAX_VALUE)) {
            mvc.perform(get("/api/orders/{id}", id).cookie(session.cookie())
                            .param("accountId", secondAccount.toString()).header("X-Account-Id", secondAccount))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Pedido no encontrado"));
            mvc.perform(head("/api/orders/{id}", id).cookie(session.cookie())).andExpect(status().isNotFound());
        }
    }

    @Test
    void onlyActiveBuyerRoleAllowsBothGetAndHead() throws Exception {
        var account = accounts.findById(firstAccount).orElseThrow();
        accounts.save(new UserAccount(account.id(), account.email(), account.passwordHash(), account.status(),
                Set.of(Role.COMPRADOR, Role.VENDEDOR)));
        Long id = seedOrder(firstAccount, "own");
        Session session = login("first@example.com");
        for (String activeRole : List.of("VENDEDOR", "COMPRADOR")) {
            mvc.perform(put("/api/auth/active-role").cookie(session.cookie()).header(session.header(), session.token())
                            .contentType("application/json").content("{\"role\":\"" + activeRole + "\"}"))
                    .andExpect(status().isOk());
            int expected = activeRole.equals("COMPRADOR") ? 200 : 403;
            for (String path : List.of("/api/orders", "/api/orders/" + id)) {
                mvc.perform(get(path).cookie(session.cookie())).andExpect(status().is(expected));
                mvc.perform(head(path).cookie(session.cookie())).andExpect(status().is(expected));
            }
        }
    }

    @Test
    void orderQueriesRequireAuthentication() throws Exception {
        for (String path : List.of("/api/orders", "/api/orders/1")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(head(path)).andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "IN_PREPARATION"})
    void cancellationChangesOnlyTheOwnedOrderAndRepeatReturns409(String from) throws Exception {
        Long id = seedOrder(firstAccount, "cancel-target");
        Long ownOther = seedOrder(firstAccount, "own-other");
        Long foreign = seedOrder(secondAccount, "foreign");
        Long historical = seedOrder(null, "historical");
        jdbc.update("UPDATE orders SET status=? WHERE id=?", from, id);
        Order before = load(id);
        Session session = login("first@example.com");
        cancel(session, id, 200);
        Order after = load(id);
        assertThat(after.status().name()).isEqualTo("CANCELLED");
        assertThat(after.paymentStatus().name()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(12);
        assertThat(jdbc.queryForMap("SELECT initiator, reason_code, cancelled_by_id FROM order_cancellations WHERE order_id=?", id))
                .containsEntry("initiator", "BUYER").containsEntry("reason_code", "CHANGED_MIND")
                .containsEntry("cancelled_by_id", firstAccount);
        assertThat(jdbc.queryForMap("SELECT from_status, to_status, actor_type, actor_id FROM order_status_history WHERE order_id=?", id))
                .containsEntry("from_status", from).containsEntry("to_status", "CANCELLED")
                .containsEntry("actor_type", "BUYER").containsEntry("actor_id", firstAccount);
        assertThat(jdbc.queryForMap("SELECT status, amount FROM refunds WHERE order_id=?", id))
                .containsEntry("status", "COMPLETED").containsEntry("amount", new BigDecimal("200.00"));
        assertThat(jdbc.queryForList("SELECT recipient_type FROM notifications WHERE reference_type='ORDER' AND reference_id=?", String.class, id.toString()))
                .containsExactlyInAnyOrder("BUYER", "STORE");
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.accountId()).isEqualTo(before.accountId());
        assertThat(after.total()).isEqualByComparingTo(before.total());
        assertThat(after.items()).isEqualTo(before.items());
        assertThat(after.addressId()).isEqualTo(before.addressId());
        assertThat(after.shippingMethod()).isEqualTo(before.shippingMethod());
        assertThat(after.transactionId()).isEqualTo(before.transactionId());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        cancel(session, id, 409);
        for (Long unchanged : List.of(ownOther, foreign, historical)) {
            assertThat(load(unchanged).status().name()).isEqualTo("CONFIRMED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(4);
        mvc.perform(get("/api/orders/{id}", id).cookie(session.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancellationHidesForeignHistoricalAndMissingOrdersDespiteSpoofedAccount() throws Exception {
        Long foreign = seedOrder(secondAccount, "foreign");
        Long historical = seedOrder(null, "historical");
        Session session = login("first@example.com");
        for (Long id : List.of(foreign, historical, Long.MAX_VALUE)) {
            mvc.perform(post("/api/orders/{id}/cancellation", id).cookie(session.cookie())
                            .header(session.header(), session.token()).header("X-Account-Id", secondAccount)
                            .param("accountId", secondAccount.toString()).contentType("application/json")
                            .content("{\"reasonCode\":\"CHANGED_MIND\"}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Pedido no encontrado"));
        }
        assertThat(load(foreign).status().name()).isEqualTo("CONFIRMED");
        assertThat(load(historical).status().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void cancellationRequiresActiveBuyerRole() throws Exception {
        var account = accounts.findById(firstAccount).orElseThrow();
        accounts.save(new UserAccount(account.id(), account.email(), account.passwordHash(), account.status(),
                Set.of(Role.COMPRADOR, Role.VENDEDOR)));
        Session session = login("first@example.com");
        Long id = seedOrder(firstAccount, "own");
        cancel(session, id, 403);
        mvc.perform(put("/api/auth/active-role").cookie(session.cookie()).header(session.header(), session.token())
                .contentType("application/json").content("{\"role\":\"VENDEDOR\"}"))
                .andExpect(status().isOk());
        cancel(session, id, 403);
        assertThat(load(id).status().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void cancellationRequiresAuthenticationAndCsrf() throws Exception {
        Long id = seedOrder(firstAccount, "own");
        Session anonymous = csrf(null);
        cancel(anonymous, id, 401);
        Session authenticated = login("first@example.com");
        mvc.perform(post("/api/orders/{id}/cancellation", id).cookie(authenticated.cookie()))
                .andExpect(status().isForbidden());
        assertThat(load(id).status().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void simultaneousCancellationRequestsPerformExactlyOneTransition() throws Exception {
        Long id = seedOrder(firstAccount, "concurrent");
        Long unaffected = seedOrder(firstAccount, "unaffected");
        Session first = login("first@example.com");
        Session second = login("first@example.com");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (Session session : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                    return mvc.perform(post("/api/orders/{id}/cancellation", id).cookie(session.cookie())
                                    .header(session.header(), session.token()).contentType("application/json")
                                    .content("{\"reasonCode\":\"CHANGED_MIND\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            boolean bothReady = ready.await(10, java.util.concurrent.TimeUnit.SECONDS);
            start.countDown();
            assertThat(bothReady).isTrue();
            assertThat(List.of(results.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                    results.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(load(id).status().name()).isEqualTo("CANCELLED");
        assertThat(load(unaffected).status().name()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY_FOR_DISPATCH", "DELIVERED", "CANCELLED", "CANCELLATION_REQUESTED"})
    void cancellationRejectsNonCancelableStatesWithoutEffects(String state) throws Exception {
        Long id = seedOrder(firstAccount, "invalid-state");
        jdbc.update("UPDATE orders SET status=? WHERE id=?", state, id);
        cancel(login("first@example.com"), id, 409);
        assertThat(load(id).status().name()).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_cancellations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds", Integer.class)).isZero();
    }

    @Test
    void otherRequiresExplanationAndPersistsIt() throws Exception {
        Long id = seedOrder(firstAccount, "other-reason");
        Session session = login("first@example.com");
        for (String body : List.of("{}", "{\"reasonCode\":\"OTHER\"}",
                "{\"reasonCode\":\"OTHER\",\"details\":\"   \"}", "{\"reasonCode\":\"OUT_OF_STOCK\"}")) {
            mvc.perform(post("/api/orders/{id}/cancellation", id).cookie(session.cookie())
                    .header(session.header(), session.token()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(load(id).status().name()).isEqualTo("CONFIRMED");
        mvc.perform(post("/api/orders/{id}/cancellation", id).cookie(session.cookie())
                .header(session.header(), session.token()).contentType("application/json")
                .content("{\"reasonCode\":\"OTHER\",\"details\":\"  Equivoqué la dirección  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("COMPLETED"));
        assertThat(jdbc.queryForMap("SELECT reason_code, details FROM order_cancellations WHERE order_id=?", id))
                .containsEntry("reason_code", "OTHER").containsEntry("details", "Equivoqué la dirección");
    }

    @Test
    void pendingRefundKeepsTheOrderCancelledAndDoesNotRestoreStockTwice() throws Exception {
        Long id = seedOrder(firstAccount, "pending-refund");
        Session session = login("first@example.com");
        refundGateway.setMode(SimulatedRefundGateway.Mode.PENDING);
        mvc.perform(post("/api/orders/{id}/cancellation", id).cookie(session.cookie())
                .header(session.header(), session.token()).contentType("application/json")
                .content("{\"reasonCode\":\"CHANGED_MIND\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.refund.status").value("PENDING"));
        cancel(session, id, 409);
        assertThat(load(id).status().name()).isEqualTo("CANCELLED");
        assertThat(load(id).paymentStatus().name()).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds", Integer.class)).isEqualTo(1);
        assertThat(refundGateway.requestCount()).isEqualTo(1);
    }

    private void cancel(Session session, Long id, int expectedStatus) throws Exception {
        mvc.perform(post("/api/orders/{id}/cancellation", id).cookie(session.cookie())
                        .header(session.header(), session.token()).contentType("application/json")
                        .content("{\"reasonCode\":\"CHANGED_MIND\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private Long seedOrder(Long accountId, String transactionId) {
        jdbc.update("""
                INSERT INTO orders(account_id,status,total,store_id,address_id,shipping_method,transaction_id,created_at,
                                   delivery_recipient_name,delivery_street,delivery_city,delivery_department,delivery_phone)
                VALUES (?,'CONFIRMED',200,1,?,'STANDARD',?,'2026-01-01 12:00:00','Ana','Calle 1','Bogotá','Bogotá','1234567')
                """, accountId, address, transactionId);
        Long id = jdbc.queryForObject("SELECT id FROM orders WHERE transaction_id=?", Long.class, transactionId);
        jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES (?,?,'Producto',2,100,200)", id, product);
        return id;
    }

    private Long pay(Session session, String method, Long spoofedOwner) throws Exception {
        long owner = json.readTree(mvc.perform(get("/api/auth/me").cookie(session.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accountId").asLong();
        Long ownedCart = json.readTree(mvc.perform(get("/api/cart").cookie(session.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("cartId").asLong();
        Long ownedAddress = address;
        if (owner == firstAccount.longValue()) {
            jdbc.update("UPDATE addresses SET account_id=? WHERE id=?", owner, address);
        } else {
            jdbc.update("INSERT INTO addresses(account_id,recipient_name,street,city,department,phone) VALUES (?,'Otro','Calle 2','Bogotá','Bogotá','1234567')", owner);
            ownedAddress = jdbc.queryForObject("SELECT MAX(id) FROM addresses", Long.class);
        }
        jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES (?,?,2)", ownedCart, product);
        jdbc.update("""
                INSERT INTO inventory_reservations(account_id,product_id,quantity,status,created_at,expires_at)
                VALUES (?,?,2,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL 1 DAY)
                """, owner, product);
        Long reservation = jdbc.queryForObject("SELECT MAX(id) FROM inventory_reservations", Long.class);
        mvc.perform(post("/api/checkout/preview").cookie(session.cookie()).header(session.header(), session.token())
                .contentType("application/json").content(json.writeValueAsString(Map.of("addressId", ownedAddress, "shippingMethod", "STANDARD"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(10200));
        var result = mvc.perform(post("/api/payments/process").cookie(session.cookie()).header(session.header(), session.token())
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "paymentMethod", method, "reservationIds", List.of(reservation), "addressId", ownedAddress,
                        "shippingMethod", "STANDARD", "accountId", spoofedOwner, "buyerId", spoofedOwner))))
                .andExpect(status().isOk()).andReturn();
        var id = json.readTree(result.getResponse().getContentAsString()).get("orderId");
        return id == null || id.isNull() ? null : id.asLong();
    }

    private Order load(Long id) {
        return new TransactionTemplate(transactions).execute(status -> OrderMapper.toDomain(orders.findById(id).orElseThrow()));
    }

    private Session login(String email) throws Exception {
        Session before = csrf(null);
        var result = mvc.perform(post("/api/auth/login").cookie(before.cookie()).header(before.header(), before.token())
                .param("email", email).param("password", "OrderPassword!123"))
                .andExpect(status().isNoContent()).andReturn();
        return csrf(result.getResponse().getCookie("SESSION"));
    }

    private Session csrf(Cookie cookie) throws Exception {
        var request = get("/api/auth/csrf");
        if (cookie != null) request.cookie(cookie);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        Cookie issued = result.getResponse().getCookie("SESSION");
        return new Session(issued == null ? cookie : issued, body.get("headerName").asText(), body.get("token").asText());
    }
    private record Session(Cookie cookie, String header, String token) {}
}
