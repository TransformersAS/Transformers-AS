package com.transformersas.marketplace;

import com.transformersas.marketplace.audit.infrastructure.persistence.repository.AuditLogRepository;
import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductRepository;
import com.transformersas.marketplace.reports.application.ContentVisibility;
import com.transformersas.marketplace.reports.application.InformationRequestService;
import com.transformersas.marketplace.reports.application.ModerationDecisionService;
import com.transformersas.marketplace.reports.application.NotificationDispatcher;
import com.transformersas.marketplace.reports.application.ReportIntakeService;
import com.transformersas.marketplace.reports.application.ReportIntakeService.EvidenceInput;
import com.transformersas.marketplace.reports.application.ReportIntakeService.Intake;
import com.transformersas.marketplace.reports.application.ReportIntakeService.SubmitReport;
import com.transformersas.marketplace.reports.application.dto.ModerationRequest;
import com.transformersas.marketplace.reports.domain.model.ModerationDecision;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.port.ContentSnapshot;
import com.transformersas.marketplace.reports.domain.port.ContentSnapshotProvider;
import com.transformersas.marketplace.reports.domain.port.ExternalNotificationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-21: moderar reportes y contenido del marketplace. */
@SpringBootTest(properties = "moderation.scheduling.enabled=false")
@Testcontainers
@AutoConfigureMockMvc
class SupportApiIntegrationTests {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("moderation_test").withUsername("test").withPassword("test");

    private static final String CASES = "/api/support/moderation/cases";
    private static final String AGENT = "agent_1";
    private static final String JUSTIFICATION = "Se confirma la violación de la norma de la plataforma.";

    /** Reloj que las pruebas mueven para probar el plazo de 72 h sin esperar. */
    static class MutableClock extends Clock {
        private volatile Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        void reset() {
            now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC; // sin cambios de horario: 72 h son siempre 72 h
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Servicio externo simulado que se puede hacer fallar. */
    static class FakeNotificationClient implements ExternalNotificationClient {
        volatile boolean failing;
        final List<String[]> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(String recipientId, String template, String payloadJson) {
            if (failing) {
                throw new IllegalStateException("servicio externo caído");
            }
            sent.add(new String[]{recipientId, template, payloadJson});
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        FakeNotificationClient fakeNotificationClient() {
            return new FakeNotificationClient();
        }

        /** Las reseñas aún no existen como módulo: este proveedor simula que exponen a su autor como dueño. */
        @Bean
        ContentSnapshotProvider reviewSnapshotProvider() {
            return new ContentSnapshotProvider() {
                @Override
                public ReportContentType type() {
                    return ReportContentType.RESENA;
                }

                @Override
                public java.util.Optional<ContentSnapshot> load(String contentId) {
                    return java.util.Optional.of(new ContentSnapshot("Reseña " + contentId, "Texto de la reseña",
                            "seller_9", java.util.Map.of()));
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired FakeNotificationClient externalService;
    @Autowired ReportIntakeService intake;
    @Autowired InformationRequestService informationRequests;
    @Autowired ModerationDecisionService decisions;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired ContentVisibility visibility;
    @Autowired ProductRepository products;

    @BeforeEach
    void cleanDatabase() {
        clock.reset();
        externalService.failing = false;
        externalService.sent.clear();
        for (String table : List.of("moderation_notifications", "moderation_referrals", "content_moderation_state",
                "information_requests", "moderation_actions", "report_evidences", "reports", "moderation_cases",
                "audit_logs", "cart_items", "carts", "products")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    // ---- utilidades -------------------------------------------------------------------------

    private Intake report(String reporter, ReportContentType type, String contentId) {
        return report(reporter, type, contentId, "SPAM", "Contenido engañoso");
    }

    private Intake report(String reporter, ReportContentType type, String contentId, String reason,
                          String description) {
        return intake.submit(new SubmitReport(reporter, type, contentId, reason, description, List.of()));
    }

    private static MockHttpServletRequestBuilder asAgent(MockHttpServletRequestBuilder request, String agent) {
        return request.with(user(agent).roles("SOPORTE")).with(csrf());
    }

    private static MockHttpServletRequestBuilder asBuyer(MockHttpServletRequestBuilder request) {
        return request.with(user("comprador_1").roles("COMPRADOR")).with(csrf());
    }

    private static MockHttpServletRequestBuilder asSupport(MockHttpServletRequestBuilder request) {
        return request.with(user(AGENT).roles("SOPORTE")).with(csrf());
    }

    private static String decisionBody(String decision, String justification) {
        return "{\"decision\":\"" + decision + "\",\"justification\":\"" + justification + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions decide(Long caseId, String agent, String decision)
            throws Exception {
        return mvc.perform(asAgent(post(CASES + "/" + caseId + "/decisions"), agent)
                .contentType(MediaType.APPLICATION_JSON).content(decisionBody(decision, JUSTIFICATION)));
    }

    private org.springframework.test.web.servlet.ResultActions askInformation(Long caseId, String agent,
                                                                             String body) throws Exception {
        return mvc.perform(asAgent(post(CASES + "/" + caseId + "/information-requests"), agent)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions respond(Long requestId, String user, String text)
            throws Exception {
        return mvc.perform(post("/api/moderation/information-requests/" + requestId + "/response")
                .with(user(user).roles("COMPRADOR")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"));
    }

    private String caseStatus(Long caseId) {
        return jdbc.queryForObject("SELECT status FROM moderation_cases WHERE id=?", String.class, caseId);
    }

    private Long requestIdOf(Long caseId) {
        return jdbc.queryForObject("SELECT id FROM information_requests WHERE case_id=? ORDER BY id DESC LIMIT 1",
                Long.class, caseId);
    }

    private String requestStatus(Long requestId) {
        return jdbc.queryForObject("SELECT status FROM information_requests WHERE id=?", String.class, requestId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Long newProduct(String name) {
        Product product = new Product();
        product.setName(name);
        product.setDescription("Descripción de " + name);
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(3);
        product.setCategory("Hogar");
        return products.save(product).getId();
    }

    // ---- acceso -----------------------------------------------------------------------------

    @Test
    void nonSupportRolesAndAnonymousCallersAreRejected() throws Exception {
        mvc.perform(get(CASES).with(user("comprador_1").roles("COMPRADOR"))).andExpect(status().isForbidden());
        mvc.perform(get(CASES).with(user("vendedor_1").roles("VENDEDOR"))).andExpect(status().isForbidden());
        mvc.perform(get(CASES)).andExpect(status().isUnauthorized());
        Intake created = report("user_123", ReportContentType.PUBLICACION, "1");
        mvc.perform(post(CASES + "/" + created.caseId() + "/claim").with(user("comprador_1").roles("COMPRADOR")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(CASES + "/" + created.caseId() + "/claim").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/moderation/information-requests/1/response").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hola\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- RF-156: cola y agrupación ----------------------------------------------------------

    @Test
    void reportsOnTheSameContentAreGroupedInOneCaseKeepingEachReporter() throws Exception {
        Intake first = intake.submit(new SubmitReport("user_a", ReportContentType.PUBLICACION, "77", "SPAM",
                "Es spam", List.of(new EvidenceInput("https://cdn/evidencia.png", "image/png", 1024L))));
        report("user_b", ReportContentType.PUBLICACION, "77", "FRAUDE", "Parece fraude");
        Intake third = report("user_c", ReportContentType.PUBLICACION, "77", "SPAM", "Otra vez");

        assertThat(third.caseId()).isEqualTo(first.caseId());
        assertThat(count("SELECT COUNT(*) FROM moderation_cases")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM reports WHERE case_id=?", first.caseId())).isEqualTo(3);

        mvc.perform(asSupport(get(CASES))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].reportCount", is(3)))
                .andExpect(jsonPath("$.items[0].status", is("PENDIENTE")));
        mvc.perform(asSupport(get(CASES + "/" + first.caseId()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.reports", hasSize(3)))
                .andExpect(jsonPath("$.reports[0].reporterId", is("user_a")))
                .andExpect(jsonPath("$.reports[0].evidences[0].fileUrl", is("https://cdn/evidencia.png")))
                .andExpect(jsonPath("$.reports[1].reporterId", is("user_b")))
                .andExpect(jsonPath("$.reports[2].reporterId", is("user_c")));
    }

    @Test
    void theSameReporterCannotInflateTheCountOfAnOpenCase() {
        report("user_a", ReportContentType.PUBLICACION, "77");

        assertThatThrownBy(() -> report("user_a", ReportContentType.PUBLICACION, "77"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("SELECT report_count FROM moderation_cases")).isEqualTo(1);
    }

    @Test
    void concurrentReportsOnTheSameContentEndUpInASingleCase() throws Exception {
        int reporters = 12;
        ExecutorService pool = Executors.newFixedThreadPool(reporters);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Intake>> futures = new ArrayList<>();
        for (int i = 0; i < reporters; i++) {
            String reporter = "user_" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return report(reporter, ReportContentType.PUBLICACION, "99");
            }));
        }
        start.countDown();
        Set<Long> caseIds = new HashSet<>();
        for (Future<Intake> future : futures) {
            caseIds.add(future.get().caseId());
        }
        pool.shutdown();

        assertThat(caseIds).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM moderation_cases")).isEqualTo(1);
        assertThat(count("SELECT report_count FROM moderation_cases")).isEqualTo(reporters);
        assertThat(count("SELECT COUNT(*) FROM reports")).isEqualTo(reporters);
    }

    @Test
    void queueShowsOpenCasesByReportCountAndSupportsFiltersAndPaging() throws Exception {
        Intake popular = report("user_a", ReportContentType.PUBLICACION, "1");
        report("user_b", ReportContentType.PUBLICACION, "1");
        Intake single = report("user_a", ReportContentType.MENSAJE, "m1");
        Intake resolved = report("user_a", ReportContentType.TIENDA, "t1");
        decide(resolved.caseId(), AGENT, "MANTENER").andExpect(status().isCreated());

        mvc.perform(asSupport(get(CASES))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.items[0].id", is(popular.caseId().intValue())))
                .andExpect(jsonPath("$.items[1].id", is(single.caseId().intValue())));
        mvc.perform(asSupport(get(CASES).param("status", "RESUELTO")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(resolved.caseId().intValue())));
        mvc.perform(asSupport(get(CASES).param("contentType", "MENSAJE")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].contentType", is("MENSAJE")));
        mvc.perform(asSupport(get(CASES).param("size", "1").param("page", "1")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(single.caseId().intValue())))
                .andExpect(jsonPath("$.totalPages", is(2)));
        mvc.perform(asSupport(get(CASES).param("status", "INEXISTENTE"))).andExpect(status().isBadRequest());
    }

    // ---- RF-157: detalle --------------------------------------------------------------------

    @Test
    void detailShowsTheReportedContentAndThePreviousCasesOfThatContent() throws Exception {
        Long productId = newProduct("Lámpara");
        Intake old = report("user_a", ReportContentType.PUBLICACION, productId.toString());
        decide(old.caseId(), AGENT, "MANTENER").andExpect(status().isCreated());
        Intake current = report("user_b", ReportContentType.PUBLICACION, productId.toString());

        mvc.perform(asSupport(get(CASES + "/" + current.caseId()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.title", is("Lámpara")))
                .andExpect(jsonPath("$.content.attributes.category", is("Hogar")))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].caseId", is(old.caseId().intValue())))
                .andExpect(jsonPath("$.history[0].decisions[0].decision", is("MANTENER")));
        mvc.perform(asSupport(get(CASES + "/999999"))).andExpect(status().isNotFound());
    }

    // ---- asignación -------------------------------------------------------------------------

    @Test
    void claimingAssignsTheCaseAndOtherAgentsCannotTakeIt() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");

        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/claim"), AGENT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("EN_REVISION")))
                .andExpect(jsonPath("$.assignedAgentId", is(AGENT)));
        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/claim"), AGENT)).andExpect(status().isOk());
        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/claim"), "agent_2"))
                .andExpect(status().isConflict());
        mvc.perform(asAgent(post(CASES + "/999999/claim"), AGENT)).andExpect(status().isNotFound());
    }

    // ---- RF-158: solicitudes de información y plazo de 72 h ---------------------------------

    @Test
    void informationRequestToTheReporterGetsA72HourDeadlineAndNotifiesThem() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");

        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"message\":\"Envíe más fotos\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetUserId", is("user_a")))
                .andExpect(jsonPath("$.status", is("ABIERTA")));

        assertThat(caseStatus(created.caseId())).isEqualTo("INFO_SOLICITADA");
        Long requestId = requestIdOf(created.caseId());
        assertThat(count("SELECT TIMESTAMPDIFF(HOUR, requested_at, due_at) FROM information_requests WHERE id=?",
                requestId)).isEqualTo(72);
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE recipient_id='user_a' "
                + "AND template='INFORMATION_REQUESTED' AND channel='INTERNAL' AND status='ENVIADA'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE recipient_id='user_a' "
                + "AND channel='EXTERNAL' AND status='PENDIENTE'")).isEqualTo(1);
    }

    @Test
    void informationRequestValidatesTargetAndProtectsReporterIdentity() throws Exception {
        Intake created = report("user_alpha", ReportContentType.PUBLICACION, "1");
        report("user_beta", ReportContentType.PUBLICACION, "1");

        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"message\":\"Más datos\"}")
                .andExpect(status().isBadRequest());
        askInformation(created.caseId(), AGENT,
                "{\"target\":\"REPORTADOR\",\"targetUserId\":\"otra_persona\",\"message\":\"Más datos\"}")
                .andExpect(status().isBadRequest());
        askInformation(created.caseId(), AGENT,
                "{\"target\":\"REPORTADOR\",\"targetUserId\":\"user_alpha\",\"message\":\"Igual que dijo user_beta\"}")
                .andExpect(status().isBadRequest());
        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"targetUserId\":\"user_alpha\","
                + "\"message\":\"\"}").andExpect(status().isBadRequest());
        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"targetUserId\":\"user_alpha\","
                + "\"message\":\"Más datos\"}").andExpect(status().isCreated());
        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"targetUserId\":\"user_alpha\","
                + "\"message\":\"Más datos otra vez\"}").andExpect(status().isConflict());
    }

    @Test
    void informationCanBeRequestedFromTheOwnerOnlyWhenTheContentModuleKnowsWhoIsIt() throws Exception {
        Intake publication = report("user_a", ReportContentType.PUBLICACION, "1");
        Intake review = report("user_a", ReportContentType.RESENA, "r1");

        askInformation(publication.caseId(), AGENT, "{\"target\":\"PROPIETARIO\",\"message\":\"Aclare\"}")
                .andExpect(status().isUnprocessableEntity());
        askInformation(review.caseId(), AGENT, "{\"target\":\"PROPIETARIO\",\"message\":\"Aclare\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.targetUserId", is("seller_9")));
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE recipient_id='seller_9' "
                + "AND channel='EXTERNAL'")).isEqualTo(1);
    }

    @Test
    void answeringInTimeRegistersTheResponseAndResumesTheReview() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"message\":\"Más datos\"}")
                .andExpect(status().isCreated());
        Long requestId = requestIdOf(created.caseId());
        clock.advance(Duration.ofHours(10));

        respond(requestId, "intruso", "hola").andExpect(status().isForbidden());
        respond(requestId, "user_a", "Aquí está la información").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESPONDIDA")));
        respond(requestId, "user_a", "otra vez").andExpect(status().isConflict());

        assertThat(caseStatus(created.caseId())).isEqualTo("EN_REVISION");
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE recipient_id=? "
                + "AND template='INFORMATION_RESPONDED'", AGENT)).isEqualTo(1);
    }

    @Test
    void theDeadlineIsInclusiveAtExactly72Hours() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"message\":\"Más datos\"}")
                .andExpect(status().isCreated());
        clock.advance(Duration.ofHours(72));

        respond(requestIdOf(created.caseId()), "user_a", "Justo a tiempo").andExpect(status().isOk());
    }

    @Test
    void whenTheDeadlinePassesTheSchedulerExpiresTheRequestAndReopensTheReview() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"message\":\"Más datos\"}")
                .andExpect(status().isCreated());
        Long requestId = requestIdOf(created.caseId());

        assertThat(informationRequests.expireOverdue()).isZero();
        clock.advance(Duration.ofHours(72).plusSeconds(1));
        assertThat(informationRequests.expireOverdue()).isEqualTo(1);
        assertThat(informationRequests.expireOverdue()).isZero();

        assertThat(requestStatus(requestId)).isEqualTo("VENCIDA");
        assertThat(caseStatus(created.caseId())).isEqualTo("EN_REVISION");
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE recipient_id=? "
                + "AND template='INFORMATION_EXPIRED'", AGENT)).isEqualTo(1);
        respond(requestId, "user_a", "Tarde").andExpect(status().isGone());
    }

    @Test
    void aLateAnswerIsRejectedEvenIfTheSchedulerHasNotRunYet() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"message\":\"Más datos\"}")
                .andExpect(status().isCreated());
        Long requestId = requestIdOf(created.caseId());
        clock.advance(Duration.ofHours(72).plusSeconds(1));

        respond(requestId, "user_a", "Tarde").andExpect(status().isGone());

        assertThat(requestStatus(requestId)).isEqualTo("VENCIDA");
        assertThat(caseStatus(created.caseId())).isEqualTo("EN_REVISION");
    }

    @Test
    void anActiveInformationRequestBlocksFinalDecisionsButNotPrecautionaryHiding() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"message\":\"Más datos\"}")
                .andExpect(status().isCreated());

        decide(created.caseId(), AGENT, "RETIRAR").andExpect(status().isUnprocessableEntity());
        decide(created.caseId(), AGENT, "OCULTAR_TEMPORALMENTE").andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseStatus", is("INFO_SOLICITADA")));

        clock.advance(Duration.ofHours(73));
        decide(created.caseId(), AGENT, "RETIRAR").andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseStatus", is("RESUELTO")));
        assertThat(count("SELECT COUNT(*) FROM information_requests WHERE status='VENCIDA'")).isEqualTo(1);
    }

    // ---- RF-159: decisiones ----------------------------------------------------------------

    @Test
    void decisionsRequireAValidDecisionAndAJustification() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        String url = CASES + "/" + created.caseId() + "/decisions";

        mvc.perform(asAgent(post(url), AGENT).contentType(MediaType.APPLICATION_JSON)
                .content(decisionBody("RETIRAR", ""))).andExpect(status().isBadRequest());
        mvc.perform(asAgent(post(url), AGENT).contentType(MediaType.APPLICATION_JSON)
                .content(decisionBody("RETIRAR", "corto"))).andExpect(status().isBadRequest());
        mvc.perform(asAgent(post(url), AGENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"RETIRAR\"}")).andExpect(status().isBadRequest());
        mvc.perform(asAgent(post(url), AGENT).contentType(MediaType.APPLICATION_JSON)
                .content(decisionBody("ESCALAR_A_ADMINISTRADOR", JUSTIFICATION))).andExpect(status().isBadRequest());
        mvc.perform(asAgent(post(CASES + "/999999/decisions"), AGENT).contentType(MediaType.APPLICATION_JSON)
                .content(decisionBody("RETIRAR", JUSTIFICATION))).andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM moderation_actions")).isZero();
        assertThat(caseStatus(created.caseId())).isEqualTo("PENDIENTE");
    }

    @Test
    void aStaleCaseVersionOrAnotherAgentIsRejected() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        String url = CASES + "/" + created.caseId() + "/decisions";

        mvc.perform(asAgent(post(url), AGENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"RETIRAR\",\"justification\":\"" + JUSTIFICATION
                        + "\",\"expectedVersion\":999}")).andExpect(status().isConflict());
        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/claim"), AGENT)).andExpect(status().isOk());
        decide(created.caseId(), "agent_2", "RETIRAR").andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM moderation_actions")).isZero();
    }

    @Test
    void decidingWithTheCurrentVersionWorks() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/claim"), AGENT)).andExpect(status().isOk());
        Long version = jdbc.queryForObject("SELECT version FROM moderation_cases WHERE id=?", Long.class,
                created.caseId());

        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/decisions"), AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"MANTENER\",\"justification\":\"" + JUSTIFICATION
                        + "\",\"expectedVersion\":" + version + "}")).andExpect(status().isCreated());
    }

    @Test
    void aResolvedCaseAcceptsNoMoreActions() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        decide(created.caseId(), AGENT, "MANTENER").andExpect(status().isCreated());

        decide(created.caseId(), AGENT, "RETIRAR").andExpect(status().isConflict());
        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/claim"), AGENT)).andExpect(status().isConflict());
        askInformation(created.caseId(), AGENT, "{\"target\":\"REPORTADOR\",\"message\":\"Más datos\"}")
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM moderation_actions")).isEqualTo(1);
    }

    @Test
    void twoConcurrentDecisionsOnTheSameCaseProduceASingleResolution() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> outcomes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            outcomes.add(pool.submit(() -> {
                start.await();
                try {
                    decisions.decide(created.caseId(), AGENT,
                            new ModerationRequest(ModerationDecision.RETIRAR, JUSTIFICATION, null));
                    return HttpStatus.CREATED;
                } catch (ResponseStatusException error) {
                    return HttpStatus.valueOf(error.getStatusCode().value());
                }
            }));
        }
        start.countDown();
        List<HttpStatus> results = new ArrayList<>();
        for (Future<HttpStatus> outcome : outcomes) {
            results.add(outcome.get());
        }
        pool.shutdown();

        assertThat(results).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(count("SELECT COUNT(*) FROM moderation_actions")).isEqualTo(1);
    }

    // ---- RF-160: aplicación de la medida ---------------------------------------------------

    @Test
    void removingAPublicationHidesItEverywhereAndClosesTheCase() throws Exception {
        Long productId = newProduct("Lámpara");
        Long other = newProduct("Mesa");
        Intake created = report("user_a", ReportContentType.PUBLICACION, productId.toString());

        decide(created.caseId(), AGENT, "RETIRAR").andExpect(status().isCreated())
                .andExpect(jsonPath("$.measureResult", is("APLICADA")))
                .andExpect(jsonPath("$.contentState", is("RETIRADO")))
                .andExpect(jsonPath("$.caseStatus", is("RESUELTO")));

        mvc.perform(asBuyer(get("/api/products"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(other.intValue())));
        mvc.perform(asBuyer(get("/api/products/" + productId))).andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM products WHERE id=?", productId)).isEqualTo(1);
        assertThat(caseStatus(created.caseId())).isEqualTo("RESUELTO");
        assertThat(jdbc.queryForObject("SELECT open_key FROM moderation_cases WHERE id=?", Object.class,
                created.caseId())).isNull();
    }

    @Test
    void hidingIsPrecautionaryAndKeepsTheCaseOpenUntilTheFinalDecision() throws Exception {
        Long productId = newProduct("Lámpara");
        Intake created = report("user_a", ReportContentType.PUBLICACION, productId.toString());

        decide(created.caseId(), AGENT, "OCULTAR_TEMPORALMENTE").andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentState", is("OCULTO_TEMPORAL")))
                .andExpect(jsonPath("$.caseStatus", is("EN_REVISION")));
        mvc.perform(asBuyer(get("/api/products"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(asBuyer(get("/api/products/" + productId))).andExpect(status().isNotFound());

        decide(created.caseId(), AGENT, "MANTENER").andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentState", is("VISIBLE")))
                .andExpect(jsonPath("$.caseStatus", is("RESUELTO")));
        mvc.perform(asBuyer(get("/api/products"))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(asBuyer(get("/api/products/" + productId))).andExpect(status().isOk());
    }

    @Test
    void keepingVisibleContentChangesNothingButIsStillRecorded() throws Exception {
        Long productId = newProduct("Lámpara");
        Intake created = report("user_a", ReportContentType.PUBLICACION, productId.toString());

        decide(created.caseId(), AGENT, "MANTENER").andExpect(status().isCreated())
                .andExpect(jsonPath("$.measureResult", is("YA_APLICADA")));
        mvc.perform(asBuyer(get("/api/products/" + productId))).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM moderation_actions")).isEqualTo(1);
    }

    @Test
    void contentAlreadyRemovedCanBeConfirmedButNotRestored() throws Exception {
        Intake first = report("user_a", ReportContentType.PUBLICACION, "5");
        decide(first.caseId(), AGENT, "RETIRAR").andExpect(status().isCreated());

        Intake second = report("user_b", ReportContentType.PUBLICACION, "5");
        decide(second.caseId(), AGENT, "MANTENER").andExpect(status().isConflict());
        decide(second.caseId(), AGENT, "OCULTAR_TEMPORALMENTE").andExpect(status().isConflict());
        decide(second.caseId(), AGENT, "RETIRAR").andExpect(status().isCreated())
                .andExpect(jsonPath("$.measureResult", is("YA_APLICADA")))
                .andExpect(jsonPath("$.caseStatus", is("RESUELTO")));
    }

    @Test
    void removedMessagesShowTheStandardTextWhileTheOriginalIsNeverTouched() throws Exception {
        Intake created = report("user_a", ReportContentType.MENSAJE, "msg_1");
        assertThat(visibility.renderMessageText("msg_1", "texto original")).isEqualTo("texto original");

        decide(created.caseId(), AGENT, "RETIRAR").andExpect(status().isCreated());

        assertThat(visibility.renderMessageText("msg_1", "texto original"))
                .isEqualTo("Mensaje retirado por moderación");
        assertThat(visibility.renderMessageText("msg_2", "otro mensaje")).isEqualTo("otro mensaje");
    }

    // ---- RF-161: auditoría, notificaciones y enmascaramiento ------------------------------

    @Test
    void everyActionIsAuditedAndQuotesInTheJustificationDoNotBreakTheModeration() throws Exception {
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        report("user_b", ReportContentType.PUBLICACION, "1");
        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/claim"), AGENT)).andExpect(status().isOk());
        String tricky = "El texto dice \\\"gratis\\\" y\\nmiente sobre el precio.";

        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/decisions"), AGENT)
                .contentType(MediaType.APPLICATION_JSON).content(decisionBody("RETIRAR", tricky)))
                .andExpect(status().isCreated());

        mvc.perform(asSupport(get(CASES + "/" + created.caseId() + "/audit"))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].action", is("REPORT_SUBMITTED")))
                .andExpect(jsonPath("$[1].action", is("REPORT_SUBMITTED")))
                .andExpect(jsonPath("$[2].action", is("CASE_CLAIMED")))
                .andExpect(jsonPath("$[2].actorId", is(AGENT)))
                .andExpect(jsonPath("$[3].action", is("MODERATION_DECISION")))
                .andExpect(jsonPath("$[3].actorId", is(AGENT)))
                .andExpect(jsonPath("$[3].result", is("SUCCESS")))
                .andExpect(jsonPath("$[3].metadata.decision", is("RETIRAR")))
                .andExpect(jsonPath("$[3].metadata.justification", is("El texto dice \"gratis\" y\nmiente sobre el precio.")))
                .andExpect(jsonPath("$[3].createdAt").exists());
        mvc.perform(get(CASES + "/" + created.caseId() + "/audit").with(user("comprador_1").roles("COMPRADOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAuditLogRepositoryOffersNoWayToDeleteEntries() {
        assertThat(Arrays.stream(AuditLogRepository.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
    }

    @Test
    void ownersAreNotifiedWithoutAnyDataThatRevealsWhoReported() throws Exception {
        Intake created = report("reporter_alpha", ReportContentType.RESENA, "r1", "SPAM",
                "Descripción secreta de reporter_alpha");
        report("reporter_beta", ReportContentType.RESENA, "r1", "ACOSO", "Otra descripción secreta");

        decide(created.caseId(), AGENT, "RETIRAR").andExpect(status().isCreated());

        List<String> ownerPayloads = jdbc.queryForList("SELECT CAST(payload AS CHAR) FROM moderation_notifications "
                + "WHERE recipient_id='seller_9'", String.class);
        assertThat(ownerPayloads).hasSize(2);
        for (String payload : ownerPayloads) {
            JsonNode tree = json.readTree(payload);
            Set<String> fields = new HashSet<>();
            tree.propertyNames().forEach(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("contentType", "contentId", "decision", "justification",
                    "reasonCategories");
            assertThat(payload).doesNotContain("reporter_alpha", "reporter_beta", "Descripción secreta",
                    "Otra descripción");
        }
        List<String> reporterPayloads = jdbc.queryForList("SELECT CAST(payload AS CHAR) FROM moderation_notifications "
                + "WHERE recipient_id IN ('reporter_alpha','reporter_beta') AND channel='EXTERNAL'", String.class);
        assertThat(reporterPayloads).hasSize(2).allSatisfy(payload -> assertThat(payload)
                .contains("CONTENIDO_RETIRADO").doesNotContain(JUSTIFICATION));

        dispatcher.dispatchDue();
        assertThat(externalService.sent).anySatisfy(sent -> {
            assertThat(sent[0]).isEqualTo("seller_9");
            assertThat(sent[2]).doesNotContain("reporter_alpha", "reporter_beta");
        });
    }

    @Test
    void aJustificationThatNamesAReporterIsRejectedAndNothingIsRecorded() throws Exception {
        Intake created = report("reporter_alpha", ReportContentType.RESENA, "r1");

        mvc.perform(asAgent(post(CASES + "/" + created.caseId() + "/decisions"), AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(decisionBody("RETIRAR", "Lo denunció reporter_alpha con pruebas.")))
                .andExpect(status().isBadRequest());

        assertThat(count("SELECT COUNT(*) FROM moderation_actions")).isZero();
        assertThat(count("SELECT COUNT(*) FROM content_moderation_state")).isZero();
        assertThat(caseStatus(created.caseId())).isEqualTo("PENDIENTE");
    }

    @Test
    void anExternalServiceOutageDoesNotBlockTheDecisionAndTheOutboxRetriesWithBackoff() throws Exception {
        externalService.failing = true;
        Intake created = report("user_a", ReportContentType.RESENA, "r1");

        decide(created.caseId(), AGENT, "RETIRAR").andExpect(status().isCreated());

        assertThat(caseStatus(created.caseId())).isEqualTo("RESUELTO");
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE channel='INTERNAL'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE channel='EXTERNAL' "
                + "AND status='PENDIENTE'")).isEqualTo(2);

        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE attempts=1 AND status='PENDIENTE' "
                + "AND last_error LIKE '%servicio externo caído%'")).isEqualTo(2);

        // Aún no toca reintentar: la espera exponencial empieza en un minuto.
        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE attempts=1")).isEqualTo(2);

        externalService.failing = false;
        clock.advance(Duration.ofMinutes(2));
        assertThat(dispatcher.dispatchDue()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE channel='EXTERNAL' "
                + "AND status='ENVIADA'")).isEqualTo(2);
        assertThat(externalService.sent).hasSize(2);
    }

    @Test
    void notificationsThatKeepFailingAreEventuallyMarkedAsFailed() throws Exception {
        externalService.failing = true;
        Intake created = report("user_a", ReportContentType.PUBLICACION, "1");
        decide(created.caseId(), AGENT, "MANTENER").andExpect(status().isCreated());

        for (int attempt = 0; attempt < 5; attempt++) {
            dispatcher.dispatchDue();
            clock.advance(Duration.ofHours(2));
        }

        assertThat(count("SELECT COUNT(*) FROM moderation_notifications WHERE channel='EXTERNAL' "
                + "AND status='FALLIDA' AND attempts=5")).isEqualTo(1);
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    // ---- CU-22: remisión ---------------------------------------------------------------------

    @Test
    void referringToAccountAdministrationOnlyRecordsTheRequest() throws Exception {
        Long productId = newProduct("Lámpara");
        Intake created = report("user_a", ReportContentType.PUBLICACION, productId.toString());
        String url = CASES + "/" + created.caseId() + "/referrals/account-admin";

        mvc.perform(asAgent(post(url), AGENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"justification\":\"corto\"}")).andExpect(status().isBadRequest());
        mvc.perform(asAgent(post(url), AGENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"justification\":\"Reincidencia: el vendedor publica fraudes repetidamente.\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.referralId").exists());

        assertThat(count("SELECT COUNT(*) FROM moderation_referrals WHERE case_id=? AND status='PENDIENTE'",
                created.caseId())).isEqualTo(1);
        assertThat(caseStatus(created.caseId())).isEqualTo("PENDIENTE");
        assertThat(count("SELECT COUNT(*) FROM content_moderation_state")).isZero();
        mvc.perform(asBuyer(get("/api/products/" + productId))).andExpect(status().isOk());
        mvc.perform(asAgent(post(CASES + "/999999/referrals/account-admin"), AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"justification\":\"Reincidencia: el vendedor publica fraudes.\"}"))
                .andExpect(status().isNotFound());
    }
}
