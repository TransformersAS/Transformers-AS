package com.transformersas.marketplace.restrictions;

import com.transformersas.marketplace.restrictions.application.usecase.ExpireDueRestrictionsUseCase;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.users.application.usecase.FindSellerAccountStanding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-22 de punta a punta. La cuenta evaluada es compradora y vendedora, dueña de la tienda 1, que tiene dos
 * antecedentes confirmados: una reclamación resuelta con reembolso y una publicación retirada por moderación.
 */
class AccountRestrictionIntegrationTests extends AbstractIntegrationTest {

    private static final String ADMIN = "/api/admin/accounts";
    private static final String RESTRICTIONS = "/api/admin/restrictions";
    private static final String STANDING = "/api/account/standing";
    private static final String EMAIL = "cuenta.evaluada@example.com";

    @Autowired ExpireDueRestrictionsUseCase sweep;
    @Autowired FindSellerAccountStanding sellerStanding;

    private Session admin;
    private Session account;
    private long adminId;
    private long accountId;
    private long product;

    @BeforeEach
    void scenario() throws Exception {
        admin = sessionWithRole("admin.cuentas@example.com", "ADMIN");
        adminId = accountIdOf("admin.cuentas@example.com");
        accountId = createAccount(EMAIL, "VENDEDOR", "COMPRADOR");
        assignStoreOwner(1, accountId);
        account = login(EMAIL);
        // Con dos roles ninguno queda activo solo; opera como vendedora para tocar su tienda.
        perform(account, put("/api/auth/active-role").contentType("application/json")
                .content("{\"role\":\"VENDEDOR\"}")).andExpect(status().isOk());
        product = seedProduct(1, "Producto evaluado", 20, "100");
        long buyer = createAccount("cliente.reclamante@example.com", "COMPRADOR");

        claim(seedOrder(1, "DELIVERED", product, 1, "100"), product, buyer, "RESOLVED", "REFUND_GRANTED");
        claim(seedOrder(1, "DELIVERED", product, 1, "100"), product, buyer, "OPEN", null);
        moderation(product, "RETIRAR");
        moderation(product, "MANTENER");
    }

    @AfterEach
    void removeModerationAndAudit() {
        jdbc.update("DELETE FROM moderation_actions WHERE agent_id = 'agente-cu22'");
        jdbc.update("DELETE FROM moderation_cases WHERE content_id = ? AND content_type = 'PUBLICACION'",
                String.valueOf(product));
        jdbc.update("DELETE FROM audit_logs WHERE entity_type = 'ACCOUNT'");
    }

    // ---------- Pasos 2 a 6: búsqueda y antecedentes ----------

    @Test
    void adminFindsTheAccountAndSeesOnlyConfirmedRecords() throws Exception {
        perform(admin, get(ADMIN).param("email", EMAIL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.roles", hasItem("VENDEDOR")));
        perform(admin, get(ADMIN).param("email", "nadie@example.com")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        perform(admin, get(ADMIN + "/" + accountId + "/evaluation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value(EMAIL))
                .andExpect(jsonPath("$.records", hasSize(2)))
                .andExpect(jsonPath("$.records[*].type", hasItem("CLAIM")))
                .andExpect(jsonPath("$.records[*].decision", hasItem("RETIRAR")))
                .andExpect(jsonPath("$.recentRecords").value(2))
                .andExpect(jsonPath("$.recidivist").value(true))
                .andExpect(jsonPath("$.activeRestrictions", hasSize(0)));
        perform(admin, get(ADMIN + "/999999/evaluation")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    // ---------- A3 y A4: solicitud de información ----------

    @Test
    void theAccountAnswersAnInformationRequestWithinSeventyTwoHours() throws Exception {
        long id = id(perform(admin, post(ADMIN + "/" + accountId + "/information-requests")
                .contentType("application/json").content("{\"message\":\"Explica la publicación retirada\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN")));
        LocalDateTime requested = jdbc.queryForObject(
                "SELECT requested_at FROM account_information_requests WHERE id = ?", LocalDateTime.class, id);
        LocalDateTime due = jdbc.queryForObject("SELECT due_at FROM account_information_requests WHERE id = ?",
                LocalDateTime.class, id);
        assertThat(due).isEqualTo(requested.plusHours(72));
        assertThat(notifications("ACCOUNT_INFORMATION_REQUESTED")).isEqualTo(1);
        assertThat(audits("ACCOUNT_INFORMATION_REQUESTED", String.valueOf(adminId))).isEqualTo(1);

        perform(admin, post(ADMIN + "/" + accountId + "/information-requests").contentType("application/json")
                .content("{\"message\":\"Una segunda pregunta simultánea\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INFORMATION_ALREADY_OPEN"));

        perform(account, get(STANDING)).andExpect(status().isOk())
                .andExpect(jsonPath("$.informationRequests[0].status").value("OPEN"));
        Session stranger = sessionWithRole("otra.cuenta@example.com", "COMPRADOR");
        perform(stranger, post("/api/account/information-requests/" + id + "/answer")
                .contentType("application/json").content("{\"response\":\"No es mía\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INFORMATION_NOT_FOUND"));

        perform(account, post("/api/account/information-requests/" + id + "/answer")
                .contentType("application/json").content("{\"response\":\"El proveedor envió un lote defectuoso.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"));
        perform(account, post("/api/account/information-requests/" + id + "/answer")
                .contentType("application/json").content("{\"response\":\"Otra vez\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INFORMATION_ALREADY_ANSWERED"));
        assertThat(audits("ACCOUNT_INFORMATION_ANSWERED", String.valueOf(accountId))).isEqualTo(1);
        perform(admin, get(ADMIN + "/" + accountId + "/evaluation"))
                .andExpect(jsonPath("$.informationRequests[0].response")
                        .value("El proveedor envió un lote defectuoso."));
    }

    @Test
    void afterSeventyTwoHoursTheAnswerIsRejectedAndTheAdminCanAskAgain() throws Exception {
        long id = id(perform(admin, post(ADMIN + "/" + accountId + "/information-requests")
                .contentType("application/json").content("{\"message\":\"Explica el incumplimiento\"}")));
        jdbc.update("UPDATE account_information_requests SET requested_at = ?, due_at = ? WHERE id = ?",
                LocalDateTime.now().minusHours(80), LocalDateTime.now().minusHours(8), id);

        perform(account, get(STANDING)).andExpect(jsonPath("$.informationRequests[0].status").value("EXPIRED"));
        perform(account, post("/api/account/information-requests/" + id + "/answer")
                .contentType("application/json").content("{\"response\":\"Tarde\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INFORMATION_EXPIRED"));
        perform(admin, post(ADMIN + "/" + accountId + "/information-requests").contentType("application/json")
                .content("{\"message\":\"Segunda solicitud tras el vencimiento\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT status FROM account_information_requests WHERE id = ?",
                String.class, id)).isEqualTo("EXPIRED");
    }

    // ---------- Pasos 8 a 18: aplicar ----------

    @Test
    void validationErrorsPointToTheFieldAndSaveNothing() throws Exception {
        restrictExpecting("{\"scope\":\"ACCOUNT\",\"reason\":\"corto\",\"permanent\":false,\"durationDays\":7}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTRICTION_REASON_REQUIRED"))
                .andExpect(jsonPath("$.details.field").value("reason"));
        restrictExpecting("{\"scope\":\"ROLE\",\"reason\":\"Reincidencia comprobada\",\"permanent\":false,"
                + "\"durationDays\":7}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTRICTION_SCOPE_ROLE_MISMATCH"));
        restrictExpecting("{\"scope\":\"ACCOUNT\",\"reason\":\"Reincidencia comprobada\",\"permanent\":false,"
                + "\"durationDays\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("durationDays"));
        restrictExpecting("{\"scope\":\"TODO\",\"reason\":\"Reincidencia comprobada\",\"permanent\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTRICTION_SCOPE_INVALID"));
        restrictExpecting("{\"scope\":\"ACCOUNT\",\"reason\":\"Reincidencia comprobada\",\"permanent\":true,"
                + "\"appliedBy\":1}")
                .andExpect(status().isBadRequest());
        assertThat(count("account_restrictions")).isZero();
        assertThat(storeStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void sellerRoleRestrictionRestrictsTheStoreAndBlocksSelling() throws Exception {
        long restriction = restrict("ROLE", "VENDEDOR", false, 7);

        assertThat(storeStatus()).isEqualTo("RESTRICTED");
        assertThat(jdbc.queryForObject("SELECT status_reason FROM stores WHERE id = 1", String.class))
                .isEqualTo("Reincidencia en productos defectuosos");
        assertThat(sellerStanding.execute(accountId).orElseThrow().canSell()).isFalse();
        assertThat(notifications("ACCOUNT_RESTRICTED")).isEqualTo(1);
        assertThat(audits("ACCOUNT_RESTRICTION_APPLIED", String.valueOf(adminId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_restriction_changes WHERE restriction_id = ? "
                + "AND change_type = 'APPLIED'", Integer.class, restriction)).isEqualTo(1);

        perform(account, post("/api/seller/products").contentType("application/json")
                .content("{\"name\":\"Nuevo\",\"price\":10,\"stock\":1,\"category\":\"Hogar\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_RESTRICTED"));

        perform(account, get(STANDING)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRestrictions", hasSize(1)))
                .andExpect(jsonPath("$.activeRestrictions[0].role").value("VENDEDOR"))
                .andExpect(jsonPath("$.activeRestrictions[0].appliedBy").doesNotExist());
        perform(admin, get(ADMIN + "/" + accountId + "/evaluation"))
                .andExpect(jsonPath("$.activeRestrictions[0].appliedBy").value(adminId));

        restrictExpecting("{\"scope\":\"ROLE\",\"role\":\"VENDEDOR\",\"reason\":\"Una segunda simultánea\","
                + "\"permanent\":true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_RESTRICTED"));
    }

    @Test
    void buyerRoleRestrictionLeavesTheStoreOperating() throws Exception {
        restrict("ROLE", "COMPRADOR", true, null);
        assertThat(storeStatus()).isEqualTo("ACTIVE");
        assertThat(sellerStanding.execute(accountId).orElseThrow().canSell()).isTrue();
    }

    // ---------- A7: modificar ----------

    @Test
    void durationAndPermanenceChangesAreJustifiedAndRecorded() throws Exception {
        long restriction = restrict("ACCOUNT", null, false, 7);
        assertThat(storeStatus()).isEqualTo("RESTRICTED");
        LocalDateTime start = jdbc.queryForObject("SELECT starts_at FROM account_restrictions WHERE id = ?",
                LocalDateTime.class, restriction);

        perform(admin, put(RESTRICTIONS + "/" + restriction + "/duration").contentType("application/json")
                .content("{\"durationDays\":14}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("justification"));
        perform(admin, put(RESTRICTIONS + "/" + restriction + "/duration").contentType("application/json")
                .content("{\"durationDays\":7,\"justification\":\"La misma duración de antes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESTRICTION_DURATION_UNCHANGED"));
        perform(admin, put(RESTRICTIONS + "/" + restriction + "/duration").contentType("application/json")
                .content("{\"durationDays\":14,\"justification\":\"Apareció una reincidencia nueva\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endsAt", startsWith(start.plusDays(14).toLocalDate().toString())));
        assertThat(notifications("ACCOUNT_RESTRICTION_CHANGED")).isEqualTo(1);
        assertThat(audits("ACCOUNT_RESTRICTION_DURATION_CHANGED", String.valueOf(adminId))).isEqualTo(1);

        perform(admin, post(RESTRICTIONS + "/" + restriction + "/permanent").contentType("application/json")
                .content("{\"justification\":\"La conducta continúa tras el aviso\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permanent").value(true))
                .andExpect(jsonPath("$.endsAt").doesNotExist());
        perform(admin, post(RESTRICTIONS + "/" + restriction + "/permanent").contentType("application/json")
                .content("{\"justification\":\"Hacerla permanente otra vez\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTRICTION_ALREADY_PERMANENT"));
        perform(admin, put(RESTRICTIONS + "/" + restriction + "/duration").contentType("application/json")
                .content("{\"durationDays\":30,\"justification\":\"Cambiar duración de una permanente\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTRICTION_IS_PERMANENT"));

        perform(admin, post(RESTRICTIONS + "/" + restriction + "/temporary").contentType("application/json")
                .content("{\"durationDays\":3,\"justification\":\"La cuenta presentó un plan de corrección\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permanent").value(false))
                .andExpect(jsonPath("$.endsAt").exists());
        assertThat(audits("ACCOUNT_RESTRICTION_MADE_PERMANENT", String.valueOf(adminId))).isEqualTo(1);
        assertThat(audits("ACCOUNT_RESTRICTION_MADE_TEMPORARY", String.valueOf(adminId))).isEqualTo(1);

        perform(admin, get(ADMIN + "/" + accountId + "/evaluation"))
                .andExpect(jsonPath("$.activeRestrictions[0].changes", hasSize(4)))
                .andExpect(jsonPath("$.activeRestrictions[0].changes[*].type", hasItem("MADE_TEMPORARY")));
        assertThat(storeStatus()).isEqualTo("RESTRICTED");
        perform(admin, put(RESTRICTIONS + "/999999/duration").contentType("application/json")
                .content("{\"durationDays\":3,\"justification\":\"No existe esta restricción\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESTRICTION_NOT_FOUND"));
    }

    // ---------- A6: retirar ----------

    @Test
    void theStoreOperatesAgainOnlyWhenNoOtherRestrictionBlocksSelling() throws Exception {
        long whole = restrict("ACCOUNT", null, true, null);
        long seller = restrict("ROLE", "VENDEDOR", false, 30);

        withdraw(whole).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        assertThat(storeStatus()).isEqualTo("RESTRICTED");

        withdraw(seller).andExpect(status().isOk());
        assertThat(storeStatus()).isEqualTo("ACTIVE");
        assertThat(sellerStanding.execute(accountId).orElseThrow().canSell()).isTrue();
        assertThat(notifications("ACCOUNT_RESTRICTION_ENDED")).isEqualTo(2);
        assertThat(audits("ACCOUNT_RESTRICTION_WITHDRAWN", String.valueOf(adminId))).isEqualTo(2);

        withdraw(seller).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTRICTION_NOT_ACTIVE"));
        perform(admin, get(ADMIN + "/" + accountId + "/evaluation"))
                .andExpect(jsonPath("$.activeRestrictions", hasSize(0)))
                .andExpect(jsonPath("$.restrictions", hasSize(2)))
                .andExpect(jsonPath("$.records[*].type", hasItem("RESTRICTION")))
                .andExpect(jsonPath("$.records[?(@.type == 'RESTRICTION')].subject", hasItem("Rol Vendedor")));
        perform(account, get(STANDING)).andExpect(jsonPath("$.activeRestrictions", hasSize(0)));
    }

    // ---------- A5: fin automático ----------

    @Test
    void theSweepEndsDueRestrictionsOnceAndFreesTheStore() throws Exception {
        long restriction = restrict("ACCOUNT", null, false, 1);
        long permanent = restrict("ROLE", "COMPRADOR", true, null);
        assertThat(sweep.runOnce()).isZero();

        jdbc.update("UPDATE account_restrictions SET starts_at = ?, ends_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(1), restriction);
        assertThat(sweep.runOnce()).isEqualTo(1);
        assertThat(sweep.runOnce()).isZero();

        assertThat(jdbc.queryForObject("SELECT status FROM account_restrictions WHERE id = ?", String.class,
                restriction)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM account_restrictions WHERE id = ?", String.class,
                permanent)).isEqualTo("ACTIVE");
        assertThat(storeStatus()).isEqualTo("ACTIVE");
        assertThat(audits("ACCOUNT_RESTRICTION_EXPIRED", "SYSTEM")).isEqualTo(1);
        perform(admin, get(ADMIN + "/" + accountId + "/evaluation"))
                .andExpect(jsonPath("$.restrictions[?(@.id == " + restriction + ")].changes[1].type")
                        .value(hasItem("EXPIRED")));
    }

    // ---------- RNF-003 ----------

    @Test
    void onlyTheAdministratorManagesRestrictions() throws Exception {
        perform(account, get(ADMIN).param("email", EMAIL)).andExpect(status().isForbidden());
        perform(account, post(ADMIN + "/" + accountId + "/restrictions").contentType("application/json")
                .content("{\"scope\":\"ACCOUNT\",\"reason\":\"Intento sin permisos\",\"permanent\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN).param("email", EMAIL)).andExpect(status().isUnauthorized());
        mvc.perform(get(STANDING)).andExpect(status().isUnauthorized());
        assertThat(count("account_restrictions")).isZero();
    }

    // ---------- Ayudas ----------

    private long restrict(String scope, String role, boolean permanent, Integer days) throws Exception {
        String body = "{\"scope\":\"" + scope + "\"" + (role == null ? "" : ",\"role\":\"" + role + "\"")
                + ",\"reason\":\"Reincidencia en productos defectuosos\",\"permanent\":" + permanent
                + (days == null ? "" : ",\"durationDays\":" + days) + "}";
        return id(restrictExpecting(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
    }

    private ResultActions restrictExpecting(String body) throws Exception {
        return perform(admin, post(ADMIN + "/" + accountId + "/restrictions").contentType("application/json")
                .content(body));
    }

    private ResultActions withdraw(long restriction) throws Exception {
        return perform(admin, post(RESTRICTIONS + "/" + restriction + "/withdraw").contentType("application/json")
                .content("{\"reason\":\"La cuenta corrigió los incumplimientos\"}"));
    }

    private long id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private String storeStatus() {
        return jdbc.queryForObject("SELECT status FROM stores WHERE id = 1", String.class);
    }

    private int notifications(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_type = 'BUYER' "
                + "AND recipient_id = ? AND type = ?", Integer.class, accountId, type);
    }

    private int audits(String action, String actor) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'ACCOUNT' AND entity_id = ? "
                + "AND action = ? AND actor_id = ?", Integer.class, String.valueOf(accountId), action, actor);
    }

    private void claim(long orderId, long productId, long buyer, String claimStatus, String resolution) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO claims(order_id, product_id, product_name, item_total, buyer_account_id, store_id,
                                   description, status, resolution, refund_amount, created_at, updated_at)
                VALUES (?, ?, 'Producto reclamado', 100, ?, 1, 'No funcionaba', ?, ?, ?, ?, ?)""",
                orderId, productId, buyer, claimStatus, resolution, resolution == null ? null : 100, now, now);
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
                VALUES (?, 'agente-cu22', ?, 'Decisión de prueba', 'EN_REVISION', 'RESUELTO', 'APLICADA', ?)""",
                caseId, decision, now);
    }
}
