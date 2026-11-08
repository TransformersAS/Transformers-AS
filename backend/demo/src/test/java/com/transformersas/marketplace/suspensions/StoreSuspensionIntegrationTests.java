package com.transformersas.marketplace.suspensions;

import com.transformersas.marketplace.suspensions.application.usecase.ExpireDueSuspensionsUseCase;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-27 de punta a punta. La tienda 1 tiene dos antecedentes confirmados: una reclamación que soporte resolvió con
 * reembolso y una publicación retirada por moderación. La tienda 2 solo tiene una reclamación abierta y un reporte que
 * soporte decidió MANTENER, que no son antecedentes.
 */
class StoreSuspensionIntegrationTests extends AbstractIntegrationTest {

    private static final String ADMIN = "/api/admin/store-evaluations";
    private static final String SUSPENSIONS = "/api/admin/store-suspensions";
    private static final String STANDING = "/api/seller/store/standing";

    @Autowired ExpireDueSuspensionsUseCase sweep;

    private Session admin;
    private Session seller;
    private long adminId;
    private long sellerId;
    private long product;
    private long otherStoreProduct;

    @BeforeEach
    void scenario() throws Exception {
        admin = sessionWithRole("admin.tiendas@example.com", "ADMIN");
        adminId = accountIdOf("admin.tiendas@example.com");
        seller = sellerOfStore("tienda.evaluada@example.com", 1);
        sellerId = accountIdOf("tienda.evaluada@example.com");
        product = seedProduct(1, "Producto evaluado", 20, "100");
        long buyer = createAccount("cliente.reclamante@example.com", "COMPRADOR");

        seedStore(2, "Tienda sin antecedentes");
        otherStoreProduct = seedProduct(2, "Producto ajeno", 5, "50");

        claim(seedOrder(1, "DELIVERED", product, 1, "100"), product, 1, buyer, "RESOLVED", "REFUND_GRANTED");
        moderation(product, "RETIRAR");
        claim(seedOrder(2, "DELIVERED", otherStoreProduct, 1, "50"), otherStoreProduct, 2, buyer, "OPEN", null);
        moderation(otherStoreProduct, "MANTENER");
    }

    @AfterEach
    void removeModerationAndAudit() {
        jdbc.update("DELETE FROM moderation_actions WHERE agent_id = 'agente-cu27'");
        jdbc.update("DELETE FROM moderation_cases WHERE content_id IN (?, ?) AND content_type = 'PUBLICACION'",
                String.valueOf(product), String.valueOf(otherStoreProduct));
        jdbc.update("DELETE FROM audit_logs WHERE entity_type = 'STORE'");
    }

    // ---------- RF-168 / RF-169: antecedentes ----------

    @Test
    void onlyStoresWithConfirmedRecordsAreListed() throws Exception {
        perform(admin, get(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].storeId").value(1))
                .andExpect(jsonPath("$[0].claims").value(1))
                .andExpect(jsonPath("$[0].moderations").value(1))
                .andExpect(jsonPath("$[0].recentRecords").value(2))
                .andExpect(jsonPath("$[0].recidivist").value(true))
                .andExpect(jsonPath("$[0].activeSuspension").value(false));
    }

    @Test
    void evaluationShowsRecordsAndWhatTheStoreStillHasInProgress() throws Exception {
        seedOrder(1, "IN_PREPARATION", product, 1, "100");
        perform(admin, get(ADMIN + "/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Tienda principal"))
                .andExpect(jsonPath("$.storeStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.records", hasSize(2)))
                .andExpect(jsonPath("$.records[*].type", hasItem("CLAIM")))
                .andExpect(jsonPath("$.records[*].decision", hasItem("RETIRAR")))
                .andExpect(jsonPath("$.records[*].decision", not(hasItem("MANTENER"))))
                .andExpect(jsonPath("$.recidivist").value(true))
                .andExpect(jsonPath("$.pending.ordersInProgress").value(1))
                .andExpect(jsonPath("$.activeSuspension").doesNotExist());
        perform(admin, get(ADMIN + "/2")).andExpect(jsonPath("$.records", hasSize(0)))
                .andExpect(jsonPath("$.pending.openClaims").value(1));
        perform(admin, get(ADMIN + "/999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }

    // ---------- Pasos 6 a 9: aclaraciones ----------

    @Test
    void sellerAnswersAClarificationWithinSeventyTwoHours() throws Exception {
        long id = id(perform(admin, post(ADMIN + "/1/clarifications").contentType("application/json")
                .content("{\"message\":\"Explica por qué retiraron la publicación\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN")));
        LocalDateTime due = jdbc.queryForObject("SELECT due_at FROM store_clarification_requests WHERE id = ?",
                LocalDateTime.class, id);
        LocalDateTime requested = jdbc.queryForObject(
                "SELECT requested_at FROM store_clarification_requests WHERE id = ?", LocalDateTime.class, id);
        assertThat(due).isEqualTo(requested.plusHours(72));
        assertThat(notifications("STORE_CLARIFICATION_REQUESTED")).isEqualTo(1);
        assertThat(audits("STORE_CLARIFICATION_REQUESTED", String.valueOf(adminId))).isEqualTo(1);

        perform(admin, post(ADMIN + "/1/clarifications").contentType("application/json")
                .content("{\"message\":\"Otra pregunta mientras la primera sigue abierta\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLARIFICATION_ALREADY_OPEN"));

        perform(seller, get(STANDING)).andExpect(jsonPath("$.clarifications[0].status").value("OPEN"));
        perform(seller, post(STANDING + "/clarifications/" + id + "/response").contentType("application/json")
                .content("{\"response\":\"El proveedor envió una versión defectuosa; ya la reemplazamos.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"));
        perform(seller, post(STANDING + "/clarifications/" + id + "/response").contentType("application/json")
                .content("{\"response\":\"Otra vez\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLARIFICATION_ALREADY_ANSWERED"));
        perform(admin, get(ADMIN + "/1"))
                .andExpect(jsonPath("$.clarifications[0].response")
                        .value("El proveedor envió una versión defectuosa; ya la reemplazamos."));
    }

    @Test
    void afterSeventyTwoHoursTheAnswerIsRejectedAndTheAdminCanAskAgain() throws Exception {
        long id = id(perform(admin, post(ADMIN + "/1/clarifications").contentType("application/json")
                .content("{\"message\":\"Explica el incumplimiento reportado\"}")));
        jdbc.update("UPDATE store_clarification_requests SET requested_at = ?, due_at = ? WHERE id = ?",
                LocalDateTime.now().minusHours(80), LocalDateTime.now().minusHours(8), id);

        perform(seller, get(STANDING)).andExpect(jsonPath("$.clarifications[0].status").value("EXPIRED"));
        perform(seller, post(STANDING + "/clarifications/" + id + "/response").contentType("application/json")
                .content("{\"response\":\"Tarde\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLARIFICATION_EXPIRED"));
        perform(admin, post(ADMIN + "/1/clarifications").contentType("application/json")
                .content("{\"message\":\"Segunda solicitud tras el vencimiento\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT status FROM store_clarification_requests WHERE id = ?", String.class,
                id)).isEqualTo("EXPIRED");
    }

    @Test
    void aSellerCannotAnswerAnotherStoreClarification() throws Exception {
        long id = id(perform(admin, post(ADMIN + "/2/clarifications").contentType("application/json")
                .content("{\"message\":\"Pregunta para la otra tienda\"}")));
        perform(seller, post(STANDING + "/clarifications/" + id + "/response").contentType("application/json")
                .content("{\"response\":\"No es mía\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLARIFICATION_NOT_FOUND"));
    }

    // ---------- A1: sin suspensión ----------

    @Test
    void reviewWithoutMeasureIsRecordedAndTheStoreKeepsOperating() throws Exception {
        perform(admin, post(ADMIN + "/1/reviews").contentType("application/json")
                .content("{\"note\":\"Los antecedentes son menores y ya fueron corregidos\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("NO_ACTION"));
        assertThat(storeStatus()).isEqualTo("ACTIVE");
        assertThat(audits("STORE_REVIEWED_NO_ACTION", String.valueOf(adminId))).isEqualTo(1);
        perform(admin, post(ADMIN + "/1/reviews").contentType("application/json").content("{\"note\":\"corta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("note"));
    }

    // ---------- Pasos 11 y 12: validación y resumen ----------

    @Test
    void previewValidatesReasonAndDurationAndSavesNothing() throws Exception {
        perform(admin, post(ADMIN + "/1/suspensions/preview").contentType("application/json")
                .content("{\"reason\":\"corto\",\"durationDays\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUSPENSION_REASON_REQUIRED"))
                .andExpect(jsonPath("$.details.field").value("reason"));
        perform(admin, post(ADMIN + "/1/suspensions/preview").contentType("application/json")
                .content("{\"reason\":\"Reincidencia en productos defectuosos\",\"durationDays\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("durationDays"));
        perform(admin, post(ADMIN + "/1/suspensions/preview").contentType("application/json")
                .content("{\"reason\":\"Reincidencia en productos defectuosos\",\"durationDays\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationDays").value(7))
                .andExpect(jsonPath("$.blocked", hasItem("Publicar productos nuevos")))
                .andExpect(jsonPath("$.allowed", hasItem("Atender reclamaciones y devoluciones")));
        assertThat(count("store_suspensions")).isZero();
        assertThat(storeStatus()).isEqualTo("ACTIVE");
    }

    // ---------- Pasos 13 a 17 y RF-171: efectos ----------

    @Test
    void suspensionBlocksNewCommercialOperationsButNotTheOnesInProgress() throws Exception {
        long existingOrder = seedOrder(1, "CONFIRMED", product, 1, "100");
        long draft = seedProduct(1, "Borrador", 3, "40");
        jdbc.update("UPDATE products SET status = 'DRAFT', active = FALSE WHERE id = ?", draft);
        long suspension = suspend(7);

        assertThat(storeStatus()).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT status_reason FROM stores WHERE id = 1", String.class))
                .startsWith("Suspensión temporal hasta el");
        assertThat(notifications("STORE_SUSPENDED")).isEqualTo(1);
        assertThat(audits("STORE_SUSPENSION_APPLIED", String.valueOf(adminId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store_reviews WHERE decision = 'SUSPENDED'",
                Integer.class)).isEqualTo(1);

        // Bloqueado: publicar, reactivar, editar y vender.
        perform(seller, post("/api/seller/products").contentType("application/json")
                .content("{\"name\":\"Nuevo\",\"price\":10,\"stock\":1,\"category\":\"Hogar\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_SUSPENDED"));
        perform(seller, post("/api/seller/products/" + draft + "/publish"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_SUSPENDED"));
        mvc.perform(get("/api/products")).andExpect(jsonPath("$[*].id", not(hasItem((int) product))))
                .andExpect(jsonPath("$[*].id", hasItem((int) otherStoreProduct)));
        Session shopper = sessionWithRole("comprador.bloqueado@example.com", "COMPRADOR");
        long address = seedAddress(shopper);
        perform(shopper, post("/api/cart/items").contentType("application/json")
                .content("{\"productId\":" + product + ",\"quantity\":1}")).andExpect(status().isCreated());
        perform(shopper, post("/api/checkout/preview").contentType("application/json")
                .content("{\"addressId\":" + address + ",\"shippingMethod\":\"STANDARD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_NOT_OPERATING"));

        // Permitido: atender lo que ya existía, pausar y consultar.
        perform(seller, post("/api/seller/orders/" + existingOrder + "/start-preparation"))
                .andExpect(status().isOk());
        perform(seller, post("/api/seller/products/" + product + "/pause")).andExpect(status().isOk());
        perform(seller, get(STANDING)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.activeSuspension.id").value(suspension))
                .andExpect(jsonPath("$.activeSuspension.appliedBy").doesNotExist())
                .andExpect(jsonPath("$.blocked", hasItem("Publicar productos nuevos")));

        perform(admin, post(ADMIN + "/1/suspensions").contentType("application/json")
                .content("{\"reason\":\"Una segunda suspensión simultánea\",\"durationDays\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_ALREADY_SUSPENDED"));
        perform(admin, get(ADMIN)).andExpect(jsonPath("$[0].activeSuspension").value(true));
    }

    // ---------- A3: modificar ----------

    @Test
    void durationChangeIsJustifiedRecordedAndNotified() throws Exception {
        long suspension = suspend(7);
        LocalDateTime start = jdbc.queryForObject("SELECT starts_at FROM store_suspensions WHERE id = ?",
                LocalDateTime.class, suspension);

        perform(admin, put(SUSPENSIONS + "/" + suspension + "/duration").contentType("application/json")
                .content("{\"durationDays\":14}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("justification"));
        perform(admin, put(SUSPENSIONS + "/" + suspension + "/duration").contentType("application/json")
                .content("{\"durationDays\":7,\"justification\":\"La misma duración de antes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUSPENSION_DURATION_UNCHANGED"));
        perform(admin, put(SUSPENSIONS + "/" + suspension + "/duration").contentType("application/json")
                .content("{\"durationDays\":14,\"justification\":\"Apareció una reincidencia nueva\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endsAt", startsWith(start.plusDays(14).toLocalDate().toString())));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store_suspension_changes WHERE suspension_id = ? "
                + "AND change_type = 'DURATION_CHANGED'", Integer.class, suspension)).isEqualTo(1);
        assertThat(notifications("STORE_SUSPENSION_CHANGED")).isEqualTo(1);
        assertThat(audits("STORE_SUSPENSION_DURATION_CHANGED", String.valueOf(adminId))).isEqualTo(1);
        assertThat(storeStatus()).isEqualTo("SUSPENDED");
    }

    @Test
    void aDurationThatWouldAlreadyBeOverMustUseLiftInstead() throws Exception {
        long suspension = suspend(7);
        jdbc.update("UPDATE store_suspensions SET starts_at = ?, ends_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(5), LocalDateTime.now().plusDays(2), suspension);
        perform(admin, put(SUSPENSIONS + "/" + suspension + "/duration").contentType("application/json")
                .content("{\"durationDays\":3,\"justification\":\"Acortarla a tres días\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUSPENSION_END_IN_PAST"));
        perform(admin, put(SUSPENSIONS + "/999/duration").contentType("application/json")
                .content("{\"durationDays\":3,\"justification\":\"No existe esta suspensión\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- A4 y A5: finalizar ----------

    @Test
    void liftingEarlyReactivatesTheStoreAndCannotBeRepeated() throws Exception {
        long suspension = suspend(30);
        perform(admin, post(SUSPENSIONS + "/" + suspension + "/lift").contentType("application/json")
                .content("{\"reason\":\"El vendedor corrigió todos los incumplimientos\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIFTED"))
                .andExpect(jsonPath("$.reactivated").value(true));
        assertThat(storeStatus()).isEqualTo("ACTIVE");
        assertThat(notifications("STORE_SUSPENSION_ENDED")).isEqualTo(1);
        assertThat(audits("STORE_SUSPENSION_LIFTED", String.valueOf(adminId))).isEqualTo(1);
        perform(seller, post("/api/seller/products").contentType("application/json")
                .content("{\"name\":\"Otra vez\",\"price\":10,\"stock\":1,\"category\":\"Inexistente\"}"))
                .andExpect(status().isBadRequest());

        perform(admin, post(SUSPENSIONS + "/" + suspension + "/lift").contentType("application/json")
                .content("{\"reason\":\"Levantarla por segunda vez\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUSPENSION_NOT_ACTIVE"));
    }

    @Test
    void theSweepEndsDueSuspensionsOnceAndReactivatesTheStore() throws Exception {
        long suspension = suspend(1);
        assertThat(sweep.runOnce()).isZero();

        jdbc.update("UPDATE store_suspensions SET starts_at = ?, ends_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(1), suspension);
        assertThat(sweep.runOnce()).isEqualTo(1);
        assertThat(sweep.runOnce()).isZero();

        assertThat(storeStatus()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM store_suspensions WHERE id = ?", String.class,
                suspension)).isEqualTo("EXPIRED");
        assertThat(audits("STORE_SUSPENSION_EXPIRED", "SYSTEM")).isEqualTo(1);
        perform(admin, get(ADMIN + "/1"))
                .andExpect(jsonPath("$.suspensions[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.suspensions[0].changes[1].type").value("EXPIRED"))
                .andExpect(jsonPath("$.suspensions[0].changes[1].actorId").doesNotExist());
    }

    @Test
    void anotherRestrictionOnTheAccountPreventsReactivation() throws Exception {
        long suspension = suspend(1);
        jdbc.update("DELETE FROM user_account_roles WHERE account_id = ? AND role = 'VENDEDOR'", sellerId);
        jdbc.update("UPDATE store_suspensions SET starts_at = ?, ends_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(1), suspension);

        assertThat(sweep.runOnce()).isEqualTo(1);
        assertThat(storeStatus()).isEqualTo("RESTRICTED");
        assertThat(jdbc.queryForObject("SELECT reactivation_blocked FROM store_suspension_changes "
                + "WHERE suspension_id = ? AND change_type = 'EXPIRED'", Boolean.class, suspension)).isTrue();
        assertThat(audits("STORE_REACTIVATION_BLOCKED", "SYSTEM")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE type = 'STORE_SUSPENSION_ENDED'",
                String.class)).contains("otra restricción vigente");
    }

    // ---------- RNF-003 ----------

    @Test
    void onlyTheAdministratorEvaluatesStores() throws Exception {
        perform(seller, get(ADMIN)).andExpect(status().isForbidden());
        Session shopper = sessionWithRole("comprador.curioso@example.com", "COMPRADOR");
        perform(shopper, post(ADMIN + "/1/suspensions").contentType("application/json")
                .content("{\"reason\":\"Intento sin permisos\",\"durationDays\":3}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        perform(admin, get(STANDING)).andExpect(status().isForbidden());
        assertThat(count("store_suspensions")).isZero();
    }

    @Test
    void unknownPropertiesInTheBodyAreRejected() throws Exception {
        perform(admin, post(ADMIN + "/1/suspensions").contentType("application/json")
                .content("{\"reason\":\"Motivo suficientemente largo\",\"durationDays\":3,\"endsAt\":\"2030-01-01\"}"))
                .andExpect(status().isBadRequest());
        assertThat(count("store_suspensions")).isZero();
    }

    // ---------- Ayudas ----------

    private long suspend(int days) throws Exception {
        return id(perform(admin, post(ADMIN + "/1/suspensions").contentType("application/json")
                .content("{\"reason\":\"Reincidencia en productos defectuosos\",\"durationDays\":" + days + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
    }

    private long id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private String storeStatus() {
        return jdbc.queryForObject("SELECT status FROM stores WHERE id = 1", String.class);
    }

    private int notifications(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_type = 'STORE' "
                + "AND recipient_id = 1 AND type = ?", Integer.class, type);
    }

    private int audits(String action, String actor) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'STORE' AND entity_id = '1' "
                + "AND action = ? AND actor_id = ?", Integer.class, action, actor);
    }

    private void claim(long orderId, long productId, long storeId, long buyer, String claimStatus, String resolution) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO claims(order_id, product_id, product_name, item_total, buyer_account_id, store_id,
                                   description, status, resolution, refund_amount, created_at, updated_at)
                VALUES (?, ?, 'Producto reclamado', 100, ?, ?, 'No funcionaba', ?, ?, ?, ?, ?)""",
                orderId, productId, buyer, storeId, claimStatus, resolution,
                resolution == null ? null : 100, now, now);
    }

    private void moderation(long contentId, String decision) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO moderation_cases(content_type, content_id, status, report_count, open_key, version,
                                             opened_at, resolved_at)
                VALUES ('PUBLICACION', ?, 'RESUELTO', 1, NULL, 0, ?, ?)""", String.valueOf(contentId), now, now);
        long caseId = jdbc.queryForObject("SELECT MAX(id) FROM moderation_cases", Long.class);
        jdbc.update("""
                INSERT INTO moderation_actions(case_id, agent_id, decision, justification, previous_status, new_status,
                                               measure_result, created_at)
                VALUES (?, 'agente-cu27', ?, 'Decisión de prueba', 'EN_REVISION', 'RESUELTO', 'APLICADA', ?)""",
                caseId, decision, now);
    }
}
