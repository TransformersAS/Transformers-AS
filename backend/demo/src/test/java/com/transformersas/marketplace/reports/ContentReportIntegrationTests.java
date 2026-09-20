package com.transformersas.marketplace.reports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-20: radicar reportes de contenido (RF-145 a RF-147) y sus alternativas A1 a A9. */
class ContentReportIntegrationTests extends ContentReportSupport {
    private Session buyer;
    private Session seller;
    private long buyerId;
    private long ownProduct;
    private long otherProduct;

    @BeforeEach
    void seed() throws Exception {
        seedStore(2, "Otra tienda");
        seller = sellerOfStore("seller@example.com", 1);
        assignStoreOwner(2, createAccount("otro.vendedor@example.com", "VENDEDOR"));
        ownProduct = seedProduct(1, "Lámpara propia", 5, "10.00");
        otherProduct = seedProduct(2, "Lámpara ajena", 5, "10.00");
        buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        buyerId = accountIdOf("buyer@example.com");
    }

    @Test
    void reasonsAreListedWithThePurchaseProblemFlag() throws Exception {
        perform(buyer, get(REPORTS + "/reasons")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='SPAM')].purchaseProblem").value(false))
                .andExpect(jsonPath("$[?(@.code=='PRODUCTO_NO_RECIBIDO')].purchaseProblem").value(true))
                .andExpect(jsonPath("$", hasSize(10)));
    }

    @Test
    void aBuyerFilesAReportAndGetsTheIdentifierStateAndDate() throws Exception {
        report(buyer, otherProduct, "SPAM").andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber()).andExpect(jsonPath("$.caseId").isNumber())
                .andExpect(jsonPath("$.status", is("PENDIENTE"))).andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.reason", is("SPAM"))).andExpect(jsonPath("$.duplicate", is(false)));

        assertThat(count("moderation_cases")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reporter_id FROM reports", String.class))
                .isEqualTo(String.valueOf(buyerId));
        assertThat(jdbc.queryForObject("SELECT content_id FROM reports", String.class))
                .isEqualTo(String.valueOf(otherProduct));
        String audit = jdbc.queryForObject(
                "SELECT metadata FROM audit_logs WHERE action = 'CONTENT_REPORT_FILED'", String.class);
        assertThat(audit).contains("correlationId").contains("COMPRADOR");
    }

    @Test
    void aSellerWithTheActiveRoleCanReportAnotherStoresPublication() throws Exception {
        report(seller, otherProduct, "PRODUCTO_PROHIBIDO").andExpect(status().isCreated());
    }

    @Test
    void reportingYourOwnPublicationIsRejectedAndLeavesNoCase() throws Exception {
        report(seller, ownProduct, "SPAM").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("REPORT_OWN_CONTENT")));
        assertThat(count("moderation_cases")).isZero();
        assertThat(count("reports")).isZero();
    }

    @Test
    void theSameUserReportingTheSameContentAndReasonGetsTheExistingReport() throws Exception {
        long first = idOf(report(buyer, otherProduct, "SPAM").andExpect(status().isCreated()), "id");

        report(buyer, otherProduct, "SPAM").andExpect(status().isOk()).andExpect(jsonPath("$.id", is((int) first)))
                .andExpect(jsonPath("$.duplicate", is(true)));

        assertThat(count("reports")).isEqualTo(1);
        assertThat(count("moderation_cases")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT report_count FROM moderation_cases", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSameUserWithAnotherReasonOnAnOpenCaseGetsAClearConflictWithTheExistingId() throws Exception {
        long first = idOf(report(buyer, otherProduct, "SPAM").andExpect(status().isCreated()), "id");

        report(buyer, otherProduct, "POSIBLE_FRAUDE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("REPORT_ALREADY_OPEN")))
                .andExpect(jsonPath("$.details.reportId", is((int) first)));
        assertThat(count("reports")).isEqualTo(1);
    }

    @Test
    void otherPeoplesReportsDoNotBlockMineAndShareTheCase() throws Exception {
        report(buyer, otherProduct, "SPAM").andExpect(status().isCreated());
        Session second = sessionWithRole("segundo@example.com", "COMPRADOR");

        report(second, otherProduct, "SPAM").andExpect(status().isCreated());

        assertThat(count("moderation_cases")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT report_count FROM moderation_cases", Integer.class)).isEqualTo(2);
        assertThat(count("reports")).isEqualTo(2);
    }

    @Test
    void anIncompleteReportNamesTheMissingFieldAndCreatesNothing() throws Exception {
        String id = String.valueOf(otherProduct);
        assertField(post(REPORTS).contentType("application/json").content(
                "{\"contentType\":\"PUBLICACION\",\"contentId\":\"" + id + "\",\"description\":\"algo\"}"), "reason");
        assertField(post(REPORTS).contentType("application/json").content(
                "{\"contentType\":\"PUBLICACION\",\"contentId\":\"" + id + "\",\"reason\":\"SPAM\"}"), "description");
        assertField(post(REPORTS).contentType("application/json").content(
                "{\"contentType\":\"PUBLICACION\",\"reason\":\"SPAM\",\"description\":\"algo\"}"), "contentId");
        assertField(post(REPORTS).contentType("application/json").content(
                "{\"contentId\":\"" + id + "\",\"reason\":\"SPAM\",\"description\":\"algo\"}"), "contentType");
        assertField(post(REPORTS).contentType("application/json").content(
                body(id, "INVENTADO", "algo")), "reason");
        assertField(post(REPORTS).contentType("application/json").content(
                body(id, "SPAM", "   ")), "description");
        assertThat(count("moderation_cases")).isZero();
    }

    private void assertField(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                             String field) throws Exception {
        perform(buyer, request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.field", is(field)));
    }

    @Test
    void aPurchaseProblemReasonIsRefusedWithAMessagePointingToClaimsAndReturns() throws Exception {
        report(buyer, otherProduct, "PRODUCTO_NO_RECIBIDO").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("REPORT_REASON_IS_PURCHASE_PROBLEM")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reclamaciones")));
        assertThat(count("moderation_cases")).isZero();
    }

    @Test
    void contentTypesWithoutAModuleAreRefusedWith422() throws Exception {
        for (String type : List.of("RESENA", "RESPUESTA_RESENA", "MENSAJE", "TIENDA")) {
            perform(buyer, post(REPORTS).contentType("application/json").content(
                    "{\"contentType\":\"" + type + "\",\"contentId\":\"1\",\"reason\":\"SPAM\",\"description\":\"x\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code", is("CONTENT_TYPE_NOT_REPORTABLE")));
        }
        perform(buyer, post(REPORTS).contentType("application/json").content(
                "{\"contentType\":\"OTRA_COSA\",\"contentId\":\"1\",\"reason\":\"SPAM\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.field", is("contentType")));
    }

    @Test
    void missingOrUnavailableContentAnswersTheSame404WithoutLeakingData() throws Exception {
        report(buyer, 987654L, "SPAM").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CONTENT_NOT_FOUND")));
        jdbc.update("UPDATE products SET active = FALSE WHERE id = ?", otherProduct);
        report(buyer, otherProduct, "SPAM").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CONTENT_NOT_FOUND")));
        jdbc.update("UPDATE products SET active = TRUE WHERE id = ?", otherProduct);
        perform(buyer, post(REPORTS).contentType("application/json").content(
                body("0" + otherProduct, "SPAM", "x"))).andExpect(status().isNotFound());
        assertThat(count("moderation_cases")).isZero();
    }

    @Test
    void aPublicationHiddenByModerationCannotBeReported() throws Exception {
        report(buyer, otherProduct, "SPAM").andExpect(status().isCreated());
        Session agent = sessionWithRole("agente@example.com", "SOPORTE");
        long caseId = jdbc.queryForObject("SELECT id FROM moderation_cases", Long.class);
        perform(agent, post(CASES + "/" + caseId + "/claim")).andExpect(status().isOk());
        perform(agent, post(CASES + "/" + caseId + "/decisions").contentType("application/json")
                .content("{\"decision\":\"RETIRAR\",\"justification\":\"Se retira por incumplir las normas.\"}"))
                .andExpect(status().isCreated());

        Session other = sessionWithRole("otro@example.com", "COMPRADOR");
        report(other, otherProduct, "SPAM").andExpect(status().isNotFound());
    }

    // ---------- Roles (RF-145) ----------

    @Test
    void onlyAnActiveBuyerOrSellerCanUseTheReportsApi() throws Exception {
        mvc.perform(get(REPORTS + "/reasons")).andExpect(status().isUnauthorized());

        Session support = sessionWithRole("soporte@example.com", "SOPORTE");
        report(support, otherProduct, "SPAM").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("REPORTER_ROLE_REQUIRED")));
        perform(support, get(REPORTS + "/mine")).andExpect(status().isForbidden());

        Session admin = sessionWithRole("admin@example.com", "ADMIN");
        report(admin, otherProduct, "SPAM").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("REPORTER_ROLE_REQUIRED")));
        assertThat(count("moderation_cases")).isZero();
    }

    @Test
    void aMultiRoleAccountReportsOnlyWithBuyerOrSellerAsTheActiveRole() throws Exception {
        createAccount("multi@example.com", "COMPRADOR", "SOPORTE");
        Session multi = login("multi@example.com");

        // Sin rol activo elegido todavía.
        report(multi, otherProduct, "SPAM").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("REPORTER_ROLE_REQUIRED")));

        Session support = activate(multi, "SOPORTE");
        report(support, otherProduct, "SPAM").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("REPORTER_ROLE_REQUIRED")));

        Session asBuyer = activate(multi, "COMPRADOR");
        report(asBuyer, otherProduct, "SPAM").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT reporter_id FROM reports", String.class))
                .isEqualTo(String.valueOf(accountIdOf("multi@example.com")));
    }

    private Session activate(Session session, String role) throws Exception {
        perform(session, put("/api/auth/active-role").contentType("application/json")
                .content("{\"role\":\"" + role + "\"}")).andExpect(status().is2xxSuccessful());
        return session;
    }

    // ---------- Privacidad del reportante ----------

    @Test
    void theOwnerNeverSeesWhoReportedTheirPublication() throws Exception {
        long reportId = idOf(report(buyer, ownProduct, "SPAM").andExpect(status().isCreated()), "id");

        perform(seller, get(REPORTS + "/mine")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        perform(seller, get(REPORTS + "/" + reportId)).andExpect(status().isNotFound());
        String publicListing = mvc.perform(get("/api/products").cookie(seller.cookie())).andReturn().getResponse()
                .getContentAsString();
        assertThat(publicListing).doesNotContain("reporter");
    }

    // ---------- Consulta (RF-149) ----------

    @Test
    void myReportsListsOnlyMineNewestFirstAndDetailOfSomeoneElsesIs404() throws Exception {
        long first = idOf(report(buyer, otherProduct, "SPAM").andExpect(status().isCreated()), "id");
        Session second = sessionWithRole("segundo@example.com", "COMPRADOR");
        long theirs = idOf(report(second, otherProduct, "SPAM").andExpect(status().isCreated()), "id");

        perform(buyer, get(REPORTS + "/mine")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is((int) first))).andExpect(jsonPath("$[0].status", is("PENDIENTE")));
        perform(buyer, get(REPORTS + "/" + theirs)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("REPORT_NOT_FOUND")));
        perform(buyer, get(REPORTS + "/" + first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.description", is("El anuncio es engañoso")));
    }

    @Test
    void theReporterSeesTheTranslatedStateTheInformationRequestAndOnlyTheFinalResult() throws Exception {
        long reportId = idOf(report(buyer, otherProduct, "SPAM").andExpect(status().isCreated()), "id");
        long caseId = caseOf(reportId);
        Session agent = sessionWithRole("agente@example.com", "SOPORTE");

        perform(agent, post(CASES + "/" + caseId + "/claim")).andExpect(status().isOk());
        perform(buyer, get(REPORTS + "/" + reportId)).andExpect(jsonPath("$.status", is("EN_REVISION")))
                .andExpect(jsonPath("$.result").doesNotExist());

        perform(agent, post(CASES + "/" + caseId + "/information-requests").contentType("application/json")
                .content("{\"target\":\"REPORTADOR\",\"targetUserId\":\"" + buyerId
                        + "\",\"message\":\"Envía más detalles del problema\"}")).andExpect(status().isCreated());
        perform(buyer, get(REPORTS + "/" + reportId)).andExpect(jsonPath("$.status", is("ESPERANDO_INFORMACION")))
                .andExpect(jsonPath("$.informationRequests", hasSize(1)))
                .andExpect(jsonPath("$.informationRequests[0].status", is("ABIERTA")))
                .andExpect(jsonPath("$.informationRequests[0].message", is("Envía más detalles del problema")))
                .andExpect(jsonPath("$.informationRequests[0].requestedBy").doesNotExist());
        perform(buyer, get(REPORTS + "/mine")).andExpect(jsonPath("$[0].awaitingYourResponse", is(true)));

        long requestId = jdbc.queryForObject("SELECT id FROM information_requests", Long.class);
        perform(buyer, post(REPORTS + "/" + reportId + "/information-requests/" + requestId + "/response")
                .contentType("application/json").content("{\"text\":\"Aquí van más detalles\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("RESPONDIDA")));
        perform(buyer, get(REPORTS + "/" + reportId)).andExpect(jsonPath("$.status", is("EN_REVISION")));

        perform(agent, post(CASES + "/" + caseId + "/decisions").contentType("application/json")
                .content("{\"decision\":\"RETIRAR\",\"justification\":\"Se retira por incumplir las normas.\"}"))
                .andExpect(status().isCreated());
        String detail = perform(buyer, get(REPORTS + "/" + reportId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESUELTO"))).andExpect(jsonPath("$.result", is("RETIRADO")))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("incumplir las normas").doesNotContain("agente")
                .doesNotContain("assignedAgentId").doesNotContain("reporterId").doesNotContain("justification");
    }

    @Test
    void aKeptContentShowsMantenidoAndAHiddenMeasureStaysOpenWithoutAResult() throws Exception {
        long reportId = idOf(report(buyer, otherProduct, "SPAM").andExpect(status().isCreated()), "id");
        long caseId = caseOf(reportId);
        Session agent = sessionWithRole("agente@example.com", "SOPORTE");
        perform(agent, post(CASES + "/" + caseId + "/claim")).andExpect(status().isOk());

        perform(agent, post(CASES + "/" + caseId + "/decisions").contentType("application/json")
                .content("{\"decision\":\"OCULTAR_TEMPORALMENTE\",\"justification\":\"Se oculta mientras se revisa.\"}"))
                .andExpect(status().isCreated());
        perform(buyer, get(REPORTS + "/" + reportId)).andExpect(jsonPath("$.status", is("EN_REVISION")))
                .andExpect(jsonPath("$.result").doesNotExist());

        perform(agent, post(CASES + "/" + caseId + "/decisions").contentType("application/json")
                .content("{\"decision\":\"MANTENER\",\"justification\":\"Se revisó y cumple las normas.\"}"))
                .andExpect(status().isCreated());
        perform(buyer, get(REPORTS + "/" + reportId)).andExpect(jsonPath("$.status", is("RESUELTO")))
                .andExpect(jsonPath("$.result", is("MANTENIDO"))).andExpect(jsonPath("$.resolvedAt").exists());
    }

    // ---------- Responder solicitudes de información (RF-148) ----------

    private long askReporter(Session agent, long caseId) throws Exception {
        perform(agent, post(CASES + "/" + caseId + "/claim")).andExpect(status().isOk());
        perform(agent, post(CASES + "/" + caseId + "/information-requests").contentType("application/json")
                .content("{\"target\":\"REPORTADOR\",\"targetUserId\":\"" + buyerId
                        + "\",\"message\":\"Envía más detalles del problema\"}")).andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM information_requests", Long.class);
    }

    private ResultActions answer(Session session, long reportId, long requestId, String text) throws Exception {
        return perform(session, post(REPORTS + "/" + reportId + "/information-requests/" + requestId + "/response")
                .contentType("application/json").content("{\"text\":\"" + text + "\"}"));
    }

    @Test
    void answeringTwiceIsAConflictAndSomeoneElseCannotAnswerMyRequest() throws Exception {
        long reportId = idOf(report(buyer, otherProduct, "SPAM").andExpect(status().isCreated()), "id");
        long requestId = askReporter(sessionWithRole("agente@example.com", "SOPORTE"), caseOf(reportId));
        Session stranger = sessionWithRole("intruso@example.com", "COMPRADOR");

        answer(stranger, reportId, requestId, "hola").andExpect(status().isNotFound());
        answer(buyer, reportId, requestId, "  ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field", is("text")));
        answer(buyer, reportId, requestId, "Detalles").andExpect(status().isOk());
        answer(buyer, reportId, requestId, "Otra vez").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INFORMATION_REQUEST_ALREADY_ANSWERED")));
        assertThat(jdbc.queryForObject("SELECT response_text FROM information_requests", String.class))
                .isEqualTo("Detalles");
    }

    @Test
    void anAnswerAfterThe72HourDeadlineIsRefusedAndTheRequestShowsAsExpired() throws Exception {
        long reportId = idOf(report(buyer, otherProduct, "SPAM").andExpect(status().isCreated()), "id");
        long requestId = askReporter(sessionWithRole("agente@example.com", "SOPORTE"), caseOf(reportId));
        // La hora de la aplicación es la de la JVM, no la de MySQL.
        jdbc.update("UPDATE information_requests SET due_at = ?", java.time.LocalDateTime.now().minusHours(1));

        perform(buyer, get(REPORTS + "/" + reportId))
                .andExpect(jsonPath("$.informationRequests[0].status", is("VENCIDA")));
        perform(buyer, get(REPORTS + "/mine")).andExpect(jsonPath("$[0].awaitingYourResponse", is(false)));
        answer(buyer, reportId, requestId, "Tarde").andExpect(status().isGone())
                .andExpect(jsonPath("$.code", is("INFORMATION_REQUEST_EXPIRED")));
    }

    @Test
    void theDeadlineIs72HoursFromTheRequest() throws Exception {
        long reportId = idOf(report(buyer, otherProduct, "SPAM").andExpect(status().isCreated()), "id");
        askReporter(sessionWithRole("agente@example.com", "SOPORTE"), caseOf(reportId));

        assertThat(jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(HOUR, requested_at, due_at) FROM information_requests", Integer.class))
                .isEqualTo(72);
    }
}
