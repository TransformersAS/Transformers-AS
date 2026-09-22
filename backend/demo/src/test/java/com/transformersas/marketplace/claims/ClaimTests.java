package com.transformersas.marketplace.claims;

import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-13: el comprador reclama, el vendedor responde y, si no hay acuerdo, decide soporte. */
class ClaimTests extends AbstractIntegrationTest {

    private static final String BUYER_API = "/api/claims";
    private static final String SELLER_API = "/api/seller/claims";
    private static final String SUPPORT_API = "/api/support/claims";

    @Autowired SimulatedRefundGateway refundGateway;

    private Session buyer;
    private Session seller;
    private Session support;
    private long buyerId;
    private long product;
    private long order;

    @BeforeEach
    void setUp() throws Exception {
        buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        buyerId = accountIdOf("buyer@example.com");
        seller = sellerOfStore("seller@example.com", 1);
        support = sessionWithRole("support@example.com", "SOPORTE");
        product = seedProduct(1, "Lámpara", 10, "100.00");
        order = seedOrder(1, "DELIVERED", product, 2, "100.00"); // lo pagado por la lámpara: 200
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyerId, order);
    }

    @AfterEach
    void restoreRefundGateway() {
        refundGateway.setMode(SimulatedRefundGateway.Mode.OK);
    }

    // ---------- Ayudas ----------

    private ResultActions asSeller(MockHttpServletRequestBuilder request) throws Exception {
        return performAsSeller(seller, 1, request);
    }

    private ResultActions asSeller(MockHttpServletRequestBuilder request, String body) throws Exception {
        return asSeller(request.contentType("application/json").content(body));
    }

    private ResultActions asBuyer(MockHttpServletRequestBuilder request, String body) throws Exception {
        return perform(buyer, request.contentType("application/json").content(body));
    }

    private ResultActions asSupport(MockHttpServletRequestBuilder request, String body) throws Exception {
        return perform(support, request.contentType("application/json").content(body));
    }

    private String openBody(long orderId, long productId, String description) {
        return """
                {"orderId":%d,"productId":%d,"description":"%s","evidenceUrls":["https://img.example/foto1.jpg"]}"""
                .formatted(orderId, productId, description);
    }

    private long openClaim() throws Exception {
        String response = asBuyer(post(BUYER_API), openBody(order, product, "Llegó sin pantalla"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private long escalatedClaim() throws Exception {
        long id = openClaim();
        perform(buyer, post(BUYER_API + "/" + id + "/escalate")).andExpect(status().isOk());
        return id;
    }

    // ---------- Abrir y consultar ----------

    @Test
    void buyerOpensAClaimAndOnlyTheBuyerAndTheStoreCanSeeIt() throws Exception {
        asBuyer(post(BUYER_API), openBody(order, product, "  Llegó sin pantalla  ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.orderId").value(order))
                .andExpect(jsonPath("$.productId").value(product))
                .andExpect(jsonPath("$.productName").value("Lámpara"))
                .andExpect(jsonPath("$.itemTotal").value(200.0))
                .andExpect(jsonPath("$.storeId").value(1))
                .andExpect(jsonPath("$.description").value("Llegó sin pantalla"))
                .andExpect(jsonPath("$.evidenceUrls", contains("https://img.example/foto1.jpg")))
                .andExpect(jsonPath("$.messages", empty()));
        long id = jdbc.queryForObject("SELECT id FROM claims", Long.class);

        perform(buyer, get(BUYER_API)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        perform(buyer, get(BUYER_API + "/" + id)).andExpect(status().isOk());
        asSeller(get(SELLER_API)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        asSeller(get(SELLER_API + "/" + id)).andExpect(status().isOk());

        // Otro comprador y otra tienda no ven nada.
        Session other = sessionWithRole("other@example.com", "COMPRADOR");
        perform(other, get(BUYER_API)).andExpect(jsonPath("$", empty()));
        perform(other, get(BUYER_API + "/" + id)).andExpect(status().isNotFound());
        seedStore(2, "Otra tienda");
        Session otherSeller = sellerOfStore("seller2@example.com", 2);
        performAsSeller(otherSeller, 2, get(SELLER_API)).andExpect(jsonPath("$", empty()));
        performAsSeller(otherSeller, 2, get(SELLER_API + "/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void invalidClaimsAreRejectedAndNothingIsSaved() throws Exception {
        long anotherProduct = seedProduct(1, "Otro", 1, "5.00");
        Session other = sessionWithRole("other@example.com", "COMPRADOR");

        asBuyer(post(BUYER_API), openBody(999_999, product, "x")).andExpect(status().isNotFound());
        // Una compra de otra persona equivale a inexistente.
        perform(other, post(BUYER_API).contentType("application/json").content(openBody(order, product, "x")))
                .andExpect(status().isNotFound());
        asBuyer(post(BUYER_API), openBody(order, anotherProduct, "x")).andExpect(status().isBadRequest());
        asBuyer(post(BUYER_API), openBody(order, product, "   ")).andExpect(status().isBadRequest());
        asBuyer(post(BUYER_API), "{\"productId\":" + product + ",\"description\":\"x\"}")
                .andExpect(status().isBadRequest());
        asBuyer(post(BUYER_API), """
                {"orderId":%d,"productId":%d,"description":"x","evidenceUrls":["a","b","c","d","e","f"]}"""
                .formatted(order, product)).andExpect(status().isBadRequest());

        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", order);
        asBuyer(post(BUYER_API), openBody(order, product, "x")).andExpect(status().isConflict());
        jdbc.update("UPDATE orders SET status = 'CANCELLATION_REQUESTED' WHERE id = ?", order);
        asBuyer(post(BUYER_API), openBody(order, product, "x")).andExpect(status().isConflict());
        assertThat(count("claims")).isZero();
    }

    @Test
    void onlyOneOpenClaimPerProductOfAPurchaseButAnotherCanStartAfterItEnds() throws Exception {
        long id = openClaim();
        asBuyer(post(BUYER_API), openBody(order, product, "otra vez")).andExpect(status().isConflict());

        asSeller(post(SELLER_API + "/" + id + "/propose"), """
                {"message":"Solución sin costo"}""").andExpect(status().isOk());
        perform(buyer, post(BUYER_API + "/" + id + "/accept")).andExpect(status().isOk());
        asBuyer(post(BUYER_API), openBody(order, product, "un problema nuevo")).andExpect(status().isCreated());
    }

    // ---------- El vendedor pide información ----------

    @Test
    void sellerRequestsInfoAndTheBuyersReplyReopensTheClaim() throws Exception {
        long id = openClaim();

        asSeller(post(SELLER_API + "/" + id + "/request-info"), "{\"message\":\"¿Puedes enviar una foto de la caja?\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INFO_REQUESTED"))
                .andExpect(jsonPath("$.messages[0].author").value("SELLER"))
                .andExpect(jsonPath("$.messages[0].kind").value("INFO_REQUEST"));
        asSeller(post(SELLER_API + "/" + id + "/request-info"), "{\"message\":\"otra vez\"}")
                .andExpect(status().isConflict());

        asBuyer(post(BUYER_API + "/" + id + "/messages"), "{\"message\":\"Aquí está la foto\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.messages[1].author").value("BUYER"));
        // Un mensaje más con el caso abierto no cambia el estado.
        asBuyer(post(BUYER_API + "/" + id + "/messages"), "{\"message\":\"Sigo esperando\"}")
                .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.messages", hasSize(3)));
        asBuyer(post(BUYER_API + "/" + id + "/messages"), "{\"message\":\" \"}").andExpect(status().isBadRequest());
        asBuyer(post(BUYER_API + "/999999/messages"), "{\"message\":\"x\"}").andExpect(status().isNotFound());
    }

    // ---------- Solución acordada ----------

    @Test
    void buyerAcceptsTheSellersSolutionAndGetsTheOfferedRefund() throws Exception {
        long id = openClaim();

        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\"Te devolvemos 50\",\"refundAmount\":50.00}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLUTION_PROPOSED"))
                .andExpect(jsonPath("$.proposedRefund").value(50.0))
                .andExpect(jsonPath("$.messages[0].kind").value("PROPOSAL"));
        // El vendedor puede mejorar su oferta mientras el comprador no responda.
        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\"Mejor 80\",\"refundAmount\":80.00}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.proposedRefund").value(80.0));

        perform(buyer, post(BUYER_API + "/" + id + "/accept")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("SOLUTION_ACCEPTED"))
                .andExpect(jsonPath("$.resolutionNote").value("Mejor 80"))
                .andExpect(jsonPath("$.refundAmount").value(80.0))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT amount FROM refunds WHERE idempotency_key = ?",
                java.math.BigDecimal.class, "claim-" + id)).isEqualByComparingTo("80.00");
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE idempotency_key = ?", String.class,
                "claim-" + id)).isEqualTo("COMPLETED");

        // Una reclamación terminada ya no admite más movimientos.
        asBuyer(post(BUYER_API + "/" + id + "/messages"), "{\"message\":\"x\"}").andExpect(status().isConflict());
        perform(buyer, post(BUYER_API + "/" + id + "/accept")).andExpect(status().isConflict());
        perform(buyer, post(BUYER_API + "/" + id + "/escalate")).andExpect(status().isConflict());
        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\"x\"}").andExpect(status().isConflict());
        asSeller(post(SELLER_API + "/" + id + "/request-info"), "{\"message\":\"x\"}").andExpect(status().isConflict());
    }

    @Test
    void aSolutionWithoutRefundResolvesWithoutRefunding() throws Exception {
        long id = openClaim();

        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\"Enviamos otra pantalla\",\"refundAmount\":0}")
                .andExpect(jsonPath("$.proposedRefund").value(nullValue()));
        perform(buyer, post(BUYER_API + "/" + id + "/accept")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.refundAmount").value(nullValue()))
                .andExpect(jsonPath("$.refundStatus").value(nullValue()));
        assertThat(count("refunds")).isZero();
    }

    @Test
    void refundsCannotExceedWhatWasPaidForTheProduct() throws Exception {
        long id = openClaim();

        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\"x\",\"refundAmount\":200.01}")
                .andExpect(status().isBadRequest());
        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\"x\",\"refundAmount\":-1}")
                .andExpect(status().isBadRequest());
        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\"x\",\"refundAmount\":200.00}")
                .andExpect(status().isOk());
        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\" \"}").andExpect(status().isBadRequest());
        asSeller(post(SELLER_API + "/999999/propose"), "{\"message\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void acceptingNeedsAProposal() throws Exception {
        long id = openClaim();
        perform(buyer, post(BUYER_API + "/" + id + "/accept")).andExpect(status().isConflict());
    }

    // ---------- Escalar a soporte ----------

    @Test
    void buyerEscalatesAndSupportGrantsARefund() throws Exception {
        long id = openClaim();

        asBuyer(post(BUYER_API + "/" + id + "/escalate"), "{\"reason\":\"El vendedor no responde\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ESCALATED"))
                .andExpect(jsonPath("$.messages[0].kind").value("ESCALATION"))
                .andExpect(jsonPath("$.messages[0].message").value("El vendedor no responde"));
        perform(buyer, post(BUYER_API + "/" + id + "/escalate")).andExpect(status().isConflict());
        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\"x\"}").andExpect(status().isConflict());

        perform(support, get(SUPPORT_API)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id));
        perform(support, get(SUPPORT_API + "/" + id)).andExpect(status().isOk());

        long agentId = accountIdOf("support@example.com");
        asSupport(post(SUPPORT_API + "/" + id + "/resolve"),
                "{\"decision\":\"REFUND_GRANTED\",\"amount\":120.00,\"note\":\"Procede el reembolso parcial\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("REFUND_GRANTED"))
                .andExpect(jsonPath("$.refundAmount").value(120.0))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.messages[1].author").value("SUPPORT"))
                .andExpect(jsonPath("$.messages[1].kind").value("DECISION"));
        assertThat(jdbc.queryForObject("SELECT resolved_by_account_id FROM claims WHERE id = ?", Long.class, id))
                .isEqualTo(agentId);
        assertThat(jdbc.queryForObject("SELECT amount FROM refunds WHERE idempotency_key = ?",
                java.math.BigDecimal.class, "claim-" + id)).isEqualByComparingTo("120.00");

        // El vendedor ve el resultado; soporte ya no la tiene en su cola.
        asSeller(get(SELLER_API + "/" + id)).andExpect(jsonPath("$.status").value("RESOLVED"));
        perform(support, get(SUPPORT_API)).andExpect(jsonPath("$", empty()));
    }

    @Test
    void escalatingWithoutAReasonUsesADefaultOneFromAnyOpenState() throws Exception {
        long id = openClaim();
        asSeller(post(SELLER_API + "/" + id + "/request-info"), "{\"message\":\"¿Detalles?\"}").andExpect(status().isOk());

        perform(buyer, post(BUYER_API + "/" + id + "/escalate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ESCALATED"))
                .andExpect(jsonPath("$.messages[1].message").value("No llegamos a un acuerdo con el vendedor"));
    }

    @Test
    void buyerCanEscalateRejectingAProposal() throws Exception {
        long id = openClaim();
        asSeller(post(SELLER_API + "/" + id + "/propose"), "{\"message\":\"Solo 10\",\"refundAmount\":10}")
                .andExpect(status().isOk());

        asBuyer(post(BUYER_API + "/" + id + "/escalate"), "{\"reason\":\"No me alcanza\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ESCALATED"));
    }

    @Test
    void supportCanRejectAClaim() throws Exception {
        long id = escalatedClaim();

        asSupport(post(SUPPORT_API + "/" + id + "/resolve"),
                "{\"decision\":\"REJECTED\",\"note\":\"No hay evidencia de defecto\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("REJECTED"))
                .andExpect(jsonPath("$.refundAmount").value(nullValue()));
        assertThat(count("refunds")).isZero();
    }

    @Test
    void supportDecisionsAreValidated() throws Exception {
        long id = escalatedClaim();
        String url = SUPPORT_API + "/" + id + "/resolve";

        asSupport(post(url), "{\"decision\":\"REFUND_GRANTED\",\"note\":\"x\"}").andExpect(status().isBadRequest());
        asSupport(post(url), "{\"decision\":\"REFUND_GRANTED\",\"amount\":0,\"note\":\"x\"}")
                .andExpect(status().isBadRequest());
        asSupport(post(url), "{\"decision\":\"REFUND_GRANTED\",\"amount\":200.01,\"note\":\"x\"}")
                .andExpect(status().isBadRequest());
        asSupport(post(url), "{\"decision\":\"REJECTED\",\"amount\":10,\"note\":\"x\"}").andExpect(status().isBadRequest());
        asSupport(post(url), "{\"decision\":\"SOLUTION_ACCEPTED\",\"note\":\"x\"}").andExpect(status().isBadRequest());
        asSupport(post(url), "{\"decision\":\"REJECTED\"}").andExpect(status().isBadRequest());
        asSupport(post(url), "{\"note\":\"x\"}").andExpect(status().isBadRequest());
        asSupport(post(SUPPORT_API + "/999999/resolve"), "{\"decision\":\"REJECTED\",\"note\":\"x\"}")
                .andExpect(status().isNotFound());
        perform(support, get(SUPPORT_API + "/999999")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM claims WHERE id = ?", String.class, id))
                .isEqualTo("ESCALATED");
    }

    @Test
    void supportCannotDecideAClaimThatWasNotEscalated() throws Exception {
        long id = openClaim();
        asSupport(post(SUPPORT_API + "/" + id + "/resolve"), "{\"decision\":\"REJECTED\",\"note\":\"x\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void supportCanListClaimsByStatus() throws Exception {
        openClaim();

        perform(support, get(SUPPORT_API)).andExpect(jsonPath("$", empty()));
        perform(support, get(SUPPORT_API + "?status=OPEN")).andExpect(jsonPath("$", hasSize(1)));
        perform(support, get(SUPPORT_API + "?status=NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void aFailedRefundDoesNotUndoTheResolution() throws Exception {
        refundGateway.setMode(SimulatedRefundGateway.Mode.UNAVAILABLE);
        long id = escalatedClaim();

        asSupport(post(SUPPORT_API + "/" + id + "/resolve"),
                "{\"decision\":\"REFUND_GRANTED\",\"amount\":50,\"note\":\"Procede\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.refundStatus").value("FAILED"));
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE idempotency_key = ?", String.class,
                "claim-" + id)).isEqualTo("FAILED");
    }

    // ---------- Roles ----------

    @Test
    void everyActorOnlyReachesItsOwnEntryPoint() throws Exception {
        long id = openClaim();

        mvc.perform(get(BUYER_API)).andExpect(status().isUnauthorized());
        mvc.perform(get(SUPPORT_API)).andExpect(status().isUnauthorized());

        perform(seller, get(BUYER_API)).andExpect(status().isForbidden());
        perform(support, get(BUYER_API + "/" + id)).andExpect(status().isForbidden());
        performAsSeller(buyer, 1, get(SELLER_API)).andExpect(status().isForbidden());
        performAsSeller(support, 1, get(SELLER_API)).andExpect(status().isForbidden());
        perform(buyer, get(SUPPORT_API)).andExpect(status().isForbidden());
        perform(seller, get(SUPPORT_API)).andExpect(status().isForbidden());
        perform(buyer, post(SUPPORT_API + "/" + id + "/resolve").contentType("application/json")
                .content("{\"decision\":\"REJECTED\",\"note\":\"x\"}")).andExpect(status().isForbidden());
    }
}
