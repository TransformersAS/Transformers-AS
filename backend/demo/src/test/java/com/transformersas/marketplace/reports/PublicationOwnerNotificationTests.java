package com.transformersas.marketplace.reports;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RF-161 con publicaciones reales: el dueño de la tienda recibe la notificación y la solicitud de información de
 * CU-21, y nada de lo que le llega revela quién reportó.
 */
class PublicationOwnerNotificationTests extends ContentReportSupport {
    private static final String SECRET = "Descripción secreta del comprador";

    private Session owner;
    private long ownerId;
    private long product;

    @BeforeEach
    void seed() throws Exception {
        owner = sellerOfStore("dueno@example.com", 1);
        ownerId = accountIdOf("dueno@example.com");
        product = seedProduct(1, "Lámpara del dueño", 5, "10.00");
    }

    @Test
    void theOwnerIsNotifiedOfTheDecisionAndAskedForInformationWithoutRevealingTheReporter() throws Exception {
        Session buyer = sessionWithRole("comprador.secreto@example.com", "COMPRADOR");
        long reportId = idOf(perform(buyer, post(REPORTS).contentType("application/json").content(
                body(String.valueOf(product), "SPAM", SECRET))).andExpect(status().isCreated()), "id");
        long caseId = caseOf(reportId);
        Session agent = sessionWithRole("agente@example.com", "SOPORTE");
        perform(agent, post(CASES + "/" + caseId + "/claim")).andExpect(status().isOk());

        perform(agent, post(CASES + "/" + caseId + "/information-requests").contentType("application/json")
                .content("{\"target\":\"PROPIETARIO\",\"message\":\"Aclare el origen del producto\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.targetUserId", is(String.valueOf(ownerId))));
        long requestId = jdbc.queryForObject("SELECT id FROM information_requests", Long.class);
        perform(owner, post("/api/moderation/information-requests/" + requestId + "/response")
                .contentType("application/json").content("{\"text\":\"Es de fabricación propia\"}"))
                .andExpect(status().isOk());
        perform(agent, post(CASES + "/" + caseId + "/decisions").contentType("application/json")
                .content("{\"decision\":\"RETIRAR\",\"justification\":\"Se retira por incumplir las normas.\"}"))
                .andExpect(status().isCreated());

        List<String> payloads = jdbc.queryForList("SELECT CAST(payload AS CHAR) FROM moderation_notifications "
                + "WHERE recipient_id = ?", String.class, String.valueOf(ownerId));
        assertThat(payloads).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moderation_notifications WHERE recipient_id = ? "
                + "AND channel = 'EXTERNAL'", Integer.class, String.valueOf(ownerId))).isGreaterThanOrEqualTo(2);
        for (String payload : payloads) {
            assertThat(payload).doesNotContain(SECRET).doesNotContain("comprador.secreto")
                    .doesNotContain("reporterId").doesNotContain("reporter_id");
            JsonNode tree = json.readTree(payload);
            Set<String> fields = new HashSet<>();
            tree.propertyNames().forEach(fields::add);
            assertThat(fields).doesNotContain("reporterId", "reporter", "description", "email");
        }
        // Lo que se le pide al dueño y lo que se guarda de su solicitud tampoco menciona al reportante.
        assertThat(jdbc.queryForObject("SELECT message FROM information_requests", String.class))
                .doesNotContain("comprador");
        // El reportante, en cambio, sí es notificado del resultado, sin la justificación.
        List<String> reporterPayloads = jdbc.queryForList("SELECT CAST(payload AS CHAR) FROM moderation_notifications "
                + "WHERE recipient_id = ?", String.class, String.valueOf(accountIdOf("comprador.secreto@example.com")));
        assertThat(reporterPayloads).isNotEmpty().allSatisfy(payload ->
                assertThat(payload).doesNotContain("incumplir las normas"));
    }
}
