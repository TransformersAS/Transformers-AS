package com.transformersas.marketplace.returns;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-19 desde la tienda y la respuesta del comprador: listar solo lo suyo, revisar, pedir información con 24 h (A5),
 * aprobar o rechazar con justificación, las marcas de atraso y la serialización de acciones simultáneas.
 */
class ReturnSellerApiTests extends ReturnsTestSupport {
    private Session buyer;
    private Session seller;
    private Session otherSeller;
    private long buyerId;

    @BeforeEach
    void seed() throws Exception {
        seedStore(2, "Otra tienda");
        seller = sellerOfStore("vendedor@example.com", 1);
        otherSeller = sellerOfStore("otro.vendedor@example.com", 2);
        buyer = sessionWithRole("comprador@example.com", "COMPRADOR");
        buyerId = accountIdOf("comprador@example.com");
    }

    private long requested() throws Exception {
        DeliveredOrder order = deliveredOrder(buyerId, LocalDateTime.now().minusDays(3));
        return idOf(requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "No enciende")
                .andExpect(status().isCreated()), "id");
    }

    private String url(long id, String action) {
        return SELLER_RETURNS + "/" + id + action;
    }

    private void review(long id) throws Exception {
        perform(seller, post(url(id, "/review"))).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("IN_REVIEW")));
    }

    private void askInformation(long id, String message) throws Exception {
        perform(seller, post(url(id, "/information-requests")).contentType("application/json")
                .content("{\"message\":\"" + message + "\"}")).andExpect(status().isCreated());
    }

    // ---------- RF-105, RF-106: listar y revisar solo lo de mi tienda ----------

    @Test
    void theStoreListsOnlyItsOwnReturnsOldestFirstAndFiltersByStatus() throws Exception {
        long first = requested();
        long second = requested();
        review(second);

        perform(seller, get(SELLER_RETURNS)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is((int) first))).andExpect(jsonPath("$[1].id", is((int) second)));
        perform(seller, get(SELLER_RETURNS + "?status=IN_REVIEW")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is((int) second)));
        perform(seller, get(SELLER_RETURNS + "?status=NOPE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("RETURN_STATUS_INVALID")));
        perform(otherSeller, get(SELLER_RETURNS)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aReturnOfAnotherStoreIsNotFoundForEveryActionAndTheBuyersIdentityIsNeverExposed() throws Exception {
        long id = requested();

        perform(otherSeller, get(url(id, ""))).andExpect(status().isNotFound());
        perform(otherSeller, post(url(id, "/review"))).andExpect(status().isNotFound());
        perform(otherSeller, post(url(id, "/reject")).contentType("application/json").content("{\"note\":\"x\"}"))
                .andExpect(status().isNotFound());
        String detail = perform(seller, get(url(id, ""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.description", is("No enciende"))).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("buyerAccountId").doesNotContain("accountId").doesNotContain("actorId");
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests", String.class)).isEqualTo("REQUESTED");
    }

    @Test
    void onlyTheStoreOwnerWithTheActiveSellerRoleUsesTheStoreEndpoints() throws Exception {
        long id = requested();

        mvc.perform(get(SELLER_RETURNS)).andExpect(status().isUnauthorized());
        perform(buyer, get(SELLER_RETURNS)).andExpect(status().isForbidden());
        perform(buyer, post(url(id, "/approve"))).andExpect(status().isForbidden());
        perform(otherSeller, get(SELLER_RETURNS).header("X-Store-Id", 1)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("STORE_NOT_AUTHORIZED")));
    }

    // ---------- RF-107, RF-108: decidir ----------

    @Test
    void reviewThenApproveLeavesTheTimelineTheAuditAndNotifiesTheBuyer() throws Exception {
        long id = requested();
        review(id);

        perform(seller, post(url(id, "/approve")).contentType("application/json").content("{\"note\":\"Se acepta\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.decision.note", is("Se acepta")));

        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.timeline[*].type", contains("REQUESTED", "REVIEW_STARTED", "APPROVED")))
                .andExpect(jsonPath("$.timeline[2].actor", is("SELLER")));
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE entity_type = 'RETURN' ORDER BY id",
                String.class)).containsExactly("RETURN_REQUESTED", "RETURN_REVIEW_STARTED", "RETURN_APPROVED");
        assertThat(jdbc.queryForMap("SELECT recipient_type, recipient_id, type FROM notifications WHERE type = "
                + "'RETURN_APPROVED'")).containsEntry("recipient_type", "BUYER").containsEntry("recipient_id", buyerId);
    }

    @Test
    void approvingWithoutJustificationIsAllowedButRejectingNeedsOne() throws Exception {
        long approved = requested();
        review(approved);
        perform(seller, post(url(approved, "/approve"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPROVED")));

        long rejected = requested();
        review(rejected);
        perform(seller, post(url(rejected, "/reject")).contentType("application/json").content("{\"note\":\"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("RETURN_JUSTIFICATION_REQUIRED")))
                .andExpect(jsonPath("$.details.field", is("note")));
        perform(seller, post(url(rejected, "/reject"))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests WHERE id = ?", String.class, rejected))
                .isEqualTo("IN_REVIEW");
    }

    @Test
    void aRejectionShowsTheJustificationToTheBuyerNotifiesThemAndClosesTheRequest() throws Exception {
        long id = requested();

        perform(seller, post(url(id, "/reject")).contentType("application/json")
                .content("{\"note\":\"El producto fue usado\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")));

        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.decision.note", is("El producto fue usado")))
                .andExpect(jsonPath("$.timeline[1].type", is("REJECTED")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'RETURN_REJECTED' AND "
                + "recipient_type = 'BUYER'", Integer.class)).isEqualTo(1);
        perform(seller, post(url(id, "/review"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_INVALID_STATE")));
        perform(seller, post(url(id, "/approve"))).andExpect(status().isConflict());
    }

    @Test
    void transitionsOutOfOrderAreConflictsAndChangeNothing() throws Exception {
        long id = requested();

        perform(seller, post(url(id, "/approve"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_INVALID_STATE")));
        perform(seller, post(url(id, "/information-requests")).contentType("application/json")
                .content("{\"message\":\"dato\"}")).andExpect(status().isConflict());
        review(id);
        perform(seller, post(url(id, "/review"))).andExpect(status().isConflict());
        assertThat(count("return_events")).isEqualTo(2);
    }

    // ---------- RF-052, RF-107, A5: información con 24 h ----------

    @Test
    void theStoreAsksForInformationTheBuyerSeesTheDeadlineAndAnswersAndItGoesBackToReview() throws Exception {
        long id = requested();
        review(id);
        askInformation(id, "Envía una foto del defecto");

        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.status", is("INFO_REQUIRED")))
                .andExpect(jsonPath("$.awaitingBuyerResponse", is(true)))
                .andExpect(jsonPath("$.informationRequests", hasSize(1)))
                .andExpect(jsonPath("$.informationRequests[0].message", is("Envía una foto del defecto")))
                .andExpect(jsonPath("$.informationRequests[0].status", is("OPEN")))
                .andExpect(jsonPath("$.informationRequests[0].expired", is(false)));
        perform(buyer, get(RETURNS)).andExpect(jsonPath("$[0].awaitingBuyerResponse", is(true)));
        LocalDateTime requestedAt = jdbc.queryForObject("SELECT requested_at FROM return_information_requests",
                LocalDateTime.class);
        assertThat(jdbc.queryForObject("SELECT due_at FROM return_information_requests", LocalDateTime.class))
                .isEqualTo(requestedAt.plusHours(24));

        perform(buyer, post(RETURNS + "/" + id + "/information-response").contentType("application/json")
                .content("{\"text\":\"Aquí está la foto\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_REVIEW"))).andExpect(jsonPath("$.awaitingBuyerResponse", is(false)))
                .andExpect(jsonPath("$.informationRequests[0].status", is("ANSWERED")))
                .andExpect(jsonPath("$.informationRequests[0].responseText", is("Aquí está la foto")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'RETURN_INFORMATION_ANSWERED'"
                + " AND recipient_type = 'STORE'", Integer.class)).isEqualTo(1);
        perform(seller, get(url(id, ""))).andExpect(jsonPath("$.timeline[*].type", contains("REQUESTED",
                "REVIEW_STARTED", "INFORMATION_REQUESTED", "INFORMATION_ANSWERED")));
    }

    @Test
    void anAnswerNeedsTextAndOnlyTheirOwnBuyerCanGiveIt() throws Exception {
        long id = requested();
        review(id);
        askInformation(id, "dato");
        createAccount("otra@example.com", "COMPRADOR");
        Session stranger = login("otra@example.com");

        perform(buyer, post(RETURNS + "/" + id + "/information-response").contentType("application/json")
                .content("{\"text\":\"  \"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("RETURN_RESPONSE_REQUIRED"))).andExpect(jsonPath("$.details.field", is("text")));
        perform(stranger, post(RETURNS + "/" + id + "/information-response").contentType("application/json")
                .content("{\"text\":\"hola\"}")).andExpect(status().isNotFound());
        perform(buyer, post(RETURNS + "/" + id + "/information-response").contentType("application/json")
                .content("{\"text\":\"listo\"}")).andExpect(status().isOk());
        perform(buyer, post(RETURNS + "/" + id + "/information-response").contentType("application/json")
                .content("{\"text\":\"otra vez\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_INVALID_STATE")));
    }

    @Test
    void withoutAnAnswerIn24HoursTheBuyerCanNoLongerAnswerAndOnlyThenMayTheStoreReject() throws Exception {
        long id = requested();
        review(id);
        askInformation(id, "dato");

        perform(seller, post(url(id, "/reject")).contentType("application/json").content("{\"note\":\"No respondió\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("RETURN_INFORMATION_PENDING")));

        jdbc.update("UPDATE return_information_requests SET due_at = ?", LocalDateTime.now().minusMinutes(1));
        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.informationRequests[0].expired", is(true)))
                .andExpect(jsonPath("$.awaitingBuyerResponse", is(false)));
        perform(buyer, post(RETURNS + "/" + id + "/information-response").contentType("application/json")
                .content("{\"text\":\"tarde\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_INFORMATION_EXPIRED")));
        perform(seller, post(url(id, "/reject")).contentType("application/json").content("{\"note\":\"No respondió a tiempo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("REJECTED")));
        // El sistema nunca rechaza solo: hasta que la tienda decide, el estado no cambió por el vencimiento.
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests", String.class)).isEqualTo("REJECTED");
    }

    @Test
    void anExpiredRequestStaysWaitingUntilTheStoreActsBecauseNothingRejectsItAutomatically() throws Exception {
        long id = requested();
        review(id);
        askInformation(id, "dato");
        jdbc.update("UPDATE return_information_requests SET due_at = ?", LocalDateTime.now().minusDays(5));

        perform(seller, get(url(id, ""))).andExpect(jsonPath("$.status", is("INFO_REQUIRED")));
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests", String.class)).isEqualTo("INFO_REQUIRED");
    }

    // ---------- D2: marcas de atraso (calculadas al consultar) ----------

    @Test
    void theSellerDecisionIsMarkedOverdueAfter72HoursWithoutDecidingAnythingByItself() throws Exception {
        long fresh = requested();
        long old = requested();
        jdbc.update("UPDATE return_requests SET created_at = ? WHERE id = ?", LocalDateTime.now().minusHours(73), old);

        perform(seller, get(SELLER_RETURNS)).andExpect(jsonPath("$[?(@.id==" + old + ")].sellerDecisionOverdue").value(true))
                .andExpect(jsonPath("$[?(@.id==" + fresh + ")].sellerDecisionOverdue").value(false));
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests WHERE id = ?", String.class, old))
                .isEqualTo("REQUESTED");
        review(old);
        perform(seller, get(url(old, ""))).andExpect(jsonPath("$.sellerDecisionOverdue", is(true)));
        askInformation(old, "dato");
        perform(seller, get(url(old, ""))).andExpect(jsonPath("$.sellerDecisionOverdue", is(false)));
    }

    @Test
    void chooseTheReturnMethodIsMarkedOverdueOnlyWhileApprovedWithoutOne() throws Exception {
        long id = requested();
        review(id);
        perform(seller, post(url(id, "/approve"))).andExpect(status().isOk());

        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.methodSelectionOverdue", is(false)));
        jdbc.update("UPDATE return_requests SET decided_at = ? WHERE id = ?", LocalDateTime.now().minusHours(73), id);
        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.methodSelectionOverdue", is(true)));
        jdbc.update("UPDATE return_requests SET return_method_code = 'PICKUP' WHERE id = ?", id);
        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.methodSelectionOverdue", is(false)));
    }

    @Test
    void afterThirdFailedPickupTheReturnStaysApprovedAndShowsPickupBlocked() throws Exception {
        long id = requested();
        review(id);
        perform(seller, post(url(id, "/approve"))).andExpect(status().isOk());
        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.pickupBlocked", is(false)));

        jdbc.update("""
                INSERT INTO return_shipments(return_id, buyer_account_id, store_id, provider_return_id, tracking_code,
                    status, failed_pickups, pickup_stopped, created_at, updated_at)
                VALUES (?, ?, 1, 'prov-1', 'TRK-1', 'PICKUP_FAILED', 3, TRUE, NOW(6), NOW(6))""", id, buyerId);
        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.pickupBlocked", is(true)));
        perform(seller, get(SELLER_RETURNS)).andExpect(jsonPath("$[0].pickupBlocked", is(true)));
        jdbc.update("DELETE FROM return_shipments");
    }

    // ---------- Serialización ----------

    @Test
    void twoSimultaneousDecisionsOnTheSameReturnCannotBothWin() throws Exception {
        long id = requested();
        review(id);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<CompletableFuture<Integer>> attempts = List.of("approve", "reject").stream()
                .map(action -> CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await();
                        return perform(seller, post(url(id, "/" + action)).contentType("application/json")
                                .content("{\"note\":\"decisión\"}")).andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })).toList();

        List<Integer> statuses = attempts.stream().map(CompletableFuture::join).sorted().toList();

        assertThat(statuses).containsExactly(200, 409);
        assertThat(count("return_events")).isEqualTo(3); // solicitud, revisión y una sola decisión
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests", String.class)).isIn("APPROVED", "REJECTED");
    }
}
