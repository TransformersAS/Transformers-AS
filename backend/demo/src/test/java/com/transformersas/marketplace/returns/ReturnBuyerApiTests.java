package com.transformersas.marketplace.returns;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-19 desde el comprador: qué puede devolver, solicitar (A1 a A4), imágenes y quién puede usar estos endpoints. */
class ReturnBuyerApiTests extends ReturnsTestSupport {
    private Session buyer;
    private Session seller;
    private long buyerId;

    @BeforeEach
    void seed() throws Exception {
        seedStore(2, "Otra tienda");
        seller = sellerOfStore("vendedor@example.com", 1);
        buyer = sessionWithRole("comprador@example.com", "COMPRADOR");
        buyerId = accountIdOf("comprador@example.com");
    }

    private DeliveredOrder delivered(int daysAgo) {
        return deliveredOrder(buyerId, LocalDateTime.now().minusDays(daysAgo));
    }

    // ---------- RF-048: pedidos y productos elegibles ----------

    @Test
    void eligibleOrdersListDeliveredOnesWithEachLineAndWhyItCannotBeReturned() throws Exception {
        DeliveredOrder ok = delivered(3);
        DeliveredOrder expired = delivered(40);
        DeliveredOrder unknown = delivered(3);
        jdbc.update("DELETE FROM order_status_history WHERE order_id = ?", unknown.orderId());
        long notDelivered = seedOrder(1, "CONFIRMED", seedProduct(1, "Otra", 5, "10.00"), 1, "10.00");
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyerId, notDelivered);
        long stranger = createAccount("otra@example.com", "COMPRADOR");
        deliveredOrder(stranger, LocalDateTime.now().minusDays(1));

        JsonNode orders = json.readTree(perform(buyer, get(RETURNS + "/eligible-orders")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(orders).hasSize(3);
        assertThat(line(orders, ok).get("eligible").asBoolean()).isTrue();
        assertThat(line(orders, ok).get("refundAmount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(line(orders, ok).get("returnWindowEndsAt").asString()).isNotBlank();
        assertThat(line(orders, expired).get("ineligibleCode").asString()).isEqualTo("RETURN_WINDOW_EXPIRED");
        assertThat(line(orders, unknown).get("ineligibleCode").asString()).isEqualTo("RETURN_DELIVERY_DATE_UNKNOWN");
        assertThat(line(orders, unknown).get("ineligibleMessage").asString()).isNotBlank();
    }

    private JsonNode line(JsonNode orders, DeliveredOrder order) {
        for (JsonNode candidate : orders) {
            if (candidate.get("orderId").asLong() == order.orderId()) {
                return candidate.get("lines").get(0);
            }
        }
        throw new AssertionError("El pedido " + order.orderId() + " no aparece");
    }

    @Test
    void aLineThatAlreadyHasARequestIsMarkedInsteadOfBeingOfferedAgain() throws Exception {
        DeliveredOrder order = delivered(3);
        long id = idOf(requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "No enciende")
                .andExpect(status().isCreated()), "id");

        JsonNode orders = json.readTree(perform(buyer, get(RETURNS + "/eligible-orders")).andReturn().getResponse()
                .getContentAsString());

        JsonNode marked = line(orders, order);
        assertThat(marked.get("eligible").asBoolean()).isFalse();
        assertThat(marked.get("ineligibleCode").asString()).isEqualTo("RETURN_ALREADY_REQUESTED");
        assertThat(marked.get("existingReturnId").asLong()).isEqualTo(id);
        assertThat(marked.get("existingReturnStatus").asString()).isEqualTo("REQUESTED");
    }

    // ---------- RF-049: solicitar ----------

    @Test
    void theBuyerRequestsAReturnAndItIsRecordedAuditedAndNotifiedToTheStore() throws Exception {
        DeliveredOrder order = delivered(3);

        requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "  No enciende  ")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status", is("REQUESTED")))
                .andExpect(jsonPath("$.duplicate", is(false))).andExpect(jsonPath("$.orderItemId", is((int) order.itemId())))
                .andExpect(jsonPath("$.evidenceCount", is(0)));

        assertThat(jdbc.queryForMap("SELECT status, description, return_window_days, refund_amount, origin, "
                + "buyer_account_id, store_id FROM return_requests"))
                .containsEntry("status", "REQUESTED").containsEntry("description", "No enciende")
                .containsEntry("return_window_days", 30).containsEntry("origin", "BUYER")
                .containsEntry("buyer_account_id", buyerId).containsEntry("store_id", 1L);
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM return_requests", java.math.BigDecimal.class))
                .isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM return_requests", LocalDateTime.class))
                .isBetween(LocalDateTime.now().minusDays(3).minusMinutes(1), LocalDateTime.now().minusDays(3).plusMinutes(1));
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events", String.class)).containsExactly("REQUESTED");
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE entity_type = 'RETURN'", String.class))
                .containsExactly("RETURN_REQUESTED");
        assertThat(jdbc.queryForMap("SELECT recipient_type, recipient_id, type FROM notifications"))
                .containsEntry("recipient_type", "STORE").containsEntry("recipient_id", 1L)
                .containsEntry("type", "RETURN_REQUESTED");
    }

    @Test
    void theSameLineAskedAgainReturnsTheExistingRequestEvenWhenItWasRejected() throws Exception {
        DeliveredOrder order = delivered(3);
        long id = idOf(requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "No enciende")
                .andExpect(status().isCreated()), "id");

        requestReturn(buyer, order.orderId(), order.itemId(), "OTHER", "Otra descripción").andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) id))).andExpect(jsonPath("$.duplicate", is(true)));
        jdbc.update("UPDATE return_requests SET status = 'REJECTED' WHERE id = ?", id);
        requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "Otra vez").andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) id))).andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.duplicate", is(true)));

        assertThat(count("return_requests")).isEqualTo(1);
        assertThat(count("return_events")).isEqualTo(1); // solo el de la solicitud original
    }

    @Test
    void anIneligibleLineIsRefusedWithACodeAndAMessageAndCreatesNothing() throws Exception {
        DeliveredOrder expired = delivered(40);
        DeliveredOrder unknownDate = delivered(3);
        jdbc.update("DELETE FROM order_status_history WHERE order_id = ?", unknownDate.orderId());
        DeliveredOrder inTransit = delivered(3);
        jdbc.update("UPDATE orders SET status = 'IN_TRANSIT' WHERE id = ?", inTransit.orderId());
        DeliveredOrder fine = delivered(3);

        requestReturn(buyer, expired.orderId(), expired.itemId(), "DEFECTIVE", "x").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("RETURN_WINDOW_EXPIRED"))).andExpect(jsonPath("$.message").isNotEmpty());
        requestReturn(buyer, unknownDate.orderId(), unknownDate.itemId(), "DEFECTIVE", "x")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("RETURN_DELIVERY_DATE_UNKNOWN")))
                .andExpect(jsonPath("$.message", is("No se pudo determinar la fecha de entrega del pedido")));
        requestReturn(buyer, inTransit.orderId(), inTransit.itemId(), "DEFECTIVE", "x")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("RETURN_ORDER_NOT_DELIVERED")));
        requestReturn(buyer, fine.orderId(), fine.itemId() + 9999, "DEFECTIVE", "x")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("RETURN_LINE_NOT_IN_ORDER")));

        assertThat(count("return_requests")).isZero();
    }

    @Test
    void anotherBuyersOrAMissingOrderLooksTheSameAndLeaksNothing() throws Exception {
        long stranger = createAccount("otra@example.com", "COMPRADOR");
        DeliveredOrder theirs = deliveredOrder(stranger, LocalDateTime.now().minusDays(1));

        requestReturn(buyer, theirs.orderId(), theirs.itemId(), "DEFECTIVE", "x").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RETURN_ORDER_NOT_FOUND")));
        requestReturn(buyer, 987654L, 1L, "DEFECTIVE", "x").andExpect(status().isNotFound());
        assertThat(count("return_requests")).isZero();
    }

    @Test
    void incompleteDataNamesTheMissingField() throws Exception {
        DeliveredOrder order = delivered(3);

        requestReturn(buyer, order.orderId(), order.itemId(), null, "algo").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("RETURN_REASON_REQUIRED"))).andExpect(jsonPath("$.details.field", is("reason")));
        requestReturn(buyer, order.orderId(), order.itemId(), "INVENTADO", "algo").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("RETURN_REASON_INVALID"))).andExpect(jsonPath("$.details.field", is("reason")));
        requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("RETURN_DESCRIPTION_REQUIRED")))
                .andExpect(jsonPath("$.details.field", is("description")));
        requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", null).andExpect(status().isBadRequest());
        assertThat(count("return_requests")).isZero();
    }

    // ---------- RF-050, A2: imágenes ----------

    @Test
    void upToThreeImagesAreStoredAndServedOnlyToTheirBuyerAndTheStoreWithPrivateCaching() throws Exception {
        DeliveredOrder order = delivered(3);
        byte[] png = image("png", 40, 30);

        long id = idOf(requestReturnWithImages(buyer, order.orderId(), order.itemId(), evidence("uno.png", png),
                evidence("..\\..\\dos.png", image("jpg", 20, 20)), evidence("", png))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.evidenceCount", is(3))), "id");

        assertThat(jdbc.queryForList("SELECT file_name FROM return_evidence_files ORDER BY ordinal", String.class))
                .containsExactly("uno.png", "dos.png", "evidencia-3.png");
        var served = perform(buyer, get(RETURNS + "/" + id + "/evidences/1")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andReturn().getResponse();
        assertThat(served.getContentAsByteArray()).isEqualTo(png);
        assertThat(served.getHeader("ETag")).startsWith("\"").endsWith("\"");
        assertThat(served.getHeader("Cache-Control")).contains("private").contains("no-cache").doesNotContain("public");
        var notModified = perform(buyer, get(RETURNS + "/" + id + "/evidences/1").header("If-None-Match",
                served.getHeader("ETag"))).andExpect(status().isNotModified()).andReturn().getResponse();
        assertThat(notModified.getHeader("Cache-Control")).contains("private").contains("no-cache")
                .doesNotContain("public");
        assertThat(notModified.getHeader("ETag")).isEqualTo(served.getHeader("ETag"));
        var forStore = perform(seller, get(SELLER_RETURNS + "/" + id + "/evidences/2")).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(forStore.getHeader("Cache-Control")).contains("private").doesNotContain("public");
        perform(buyer, get(RETURNS + "/" + id + "/evidences/9")).andExpect(status().isNotFound());
    }

    @Test
    void anInvalidImageIsRefusedWithItsReasonAndPositionAndNothingIsCreated() throws Exception {
        DeliveredOrder order = delivered(3);
        byte[] png = image("png", 8, 8);

        requestReturnWithImages(buyer, order.orderId(), order.itemId(), evidence("ok.png", png),
                evidence("notas.png", "esto no es una imagen".getBytes())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("IMAGE_FORMAT_UNSUPPORTED")))
                .andExpect(jsonPath("$.details.field", is("evidences[1]"))).andExpect(jsonPath("$.message", startsWith("Imagen 2")));
        requestReturnWithImages(buyer, order.orderId(), order.itemId(), evidence("vacia.png", new byte[0]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("IMAGE_EMPTY")));
        requestReturnWithImages(buyer, order.orderId(), order.itemId(), evidence("rota.png",
                java.util.Arrays.copyOf(png, png.length / 2))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("IMAGE_CORRUPT")));
        requestReturnWithImages(buyer, order.orderId(), order.itemId(), evidence("1.png", png), evidence("2.png", png),
                evidence("3.png", png), evidence("4.png", png)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("RETURN_TOO_MANY_IMAGES")));

        assertThat(count("return_requests")).isZero();
        assertThat(count("return_evidence_files")).isZero();
    }

    @Test
    void aFormWithoutFilesIsNotAnImageAndAnotherBuyerCannotSeeMyEvidence() throws Exception {
        DeliveredOrder order = delivered(3);
        long id = idOf(requestReturnWithImages(buyer, order.orderId(), order.itemId(),
                new MockMultipartFile("evidences", "", "application/octet-stream", new byte[0]))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.evidenceCount", is(0))), "id");
        long stranger = createAccount("otra@example.com", "COMPRADOR");
        Session other = login("otra@example.com");

        perform(other, get(RETURNS + "/" + id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("RETURN_NOT_FOUND")));
        perform(other, get(RETURNS + "/" + id + "/evidences/1")).andExpect(status().isNotFound());
        assertThat(stranger).isPositive();
    }

    // ---------- Consulta del comprador (RF-051) ----------

    @Test
    void theBuyerListsAndReadsOnlyTheirReturnsWithTheTimelineAndNoAccountIds() throws Exception {
        DeliveredOrder order = delivered(3);
        long id = idOf(requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "No enciende")
                .andExpect(status().isCreated()), "id");

        perform(buyer, get(RETURNS)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is((int) id))).andExpect(jsonPath("$[0].status", is("REQUESTED")));
        String detail = perform(buyer, get(RETURNS + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.description", is("No enciende")))
                .andExpect(jsonPath("$.reasonLabel").isNotEmpty())
                .andExpect(jsonPath("$.timeline", hasSize(1))).andExpect(jsonPath("$.timeline[0].type", is("REQUESTED")))
                .andExpect(jsonPath("$.timeline[0].actor", is("BUYER"))).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("buyerAccountId").doesNotContain("actorId").doesNotContain("accountId");
    }

    // ---------- RNF-003: roles ----------

    @Test
    void onlyTheActiveBuyerRoleUsesTheBuyerEndpoints() throws Exception {
        mvc.perform(get(RETURNS)).andExpect(status().isUnauthorized());
        perform(seller, get(RETURNS)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("RETURN_BUYER_ROLE_REQUIRED")));
        Session support = sessionWithRole("soporte@example.com", "SOPORTE");
        perform(support, get(RETURNS + "/eligible-orders")).andExpect(status().isForbidden());

        createAccount("multi@example.com", "COMPRADOR", "VENDEDOR");
        Session multi = login("multi@example.com");
        perform(multi, get(RETURNS)).andExpect(status().isForbidden());
        perform(multi, put("/api/auth/active-role").contentType("application/json").content("{\"role\":\"VENDEDOR\"}"))
                .andExpect(status().is2xxSuccessful());
        perform(multi, get(RETURNS)).andExpect(status().isForbidden());
        perform(multi, put("/api/auth/active-role").contentType("application/json").content("{\"role\":\"COMPRADOR\"}"))
                .andExpect(status().is2xxSuccessful());
        perform(multi, get(RETURNS)).andExpect(status().isOk());
    }
}
