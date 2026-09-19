package com.transformersas.marketplace.reports;

import com.transformersas.marketplace.reports.application.ReportIntakeService;
import com.transformersas.marketplace.reports.application.ReportIntakeService.Intake;
import com.transformersas.marketplace.reports.application.ReportIntakeService.SubmitReport;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-21 con la autenticación real de CU-08 (sesión por cookie y CSRF): solo soporte accede a la
 * moderación y el agente registrado es el id de su cuenta.
 */
@SpringBootTest(properties = "moderation.scheduling.enabled=false")
@Testcontainers
@AutoConfigureMockMvc
class SupportSessionIntegrationTests {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("support_session_test").withUsername("test").withPassword("test");

    private static final String PASSWORD = "TestPassword!123";
    private static final String CASES = "/api/support/moderation/cases";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ReportIntakeService intake;

    private Long supportId;

    @BeforeEach
    void prepare() {
        for (String table : List.of("moderation_notifications", "moderation_referrals", "content_moderation_state",
                "information_requests", "moderation_actions", "report_evidences", "reports", "moderation_cases",
                "audit_logs")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM SPRING_SESSION");
        jdbc.update("DELETE FROM user_account_roles");
        jdbc.update("DELETE FROM user_accounts");
        String hash = encoder.encode(PASSWORD);
        supportId = accounts.save(new UserAccount(null, "agente@example.com", hash, AccountStatus.ACTIVA,
                Set.of(Role.SOPORTE))).id();
        accounts.save(new UserAccount(null, "comprador@example.com", hash, AccountStatus.ACTIVA,
                Set.of(Role.COMPRADOR)));
    }

    @Test
    void onlyASupportSessionCanUseTheModerationApiAndTheAgentIsTheAccountId() throws Exception {
        Intake created = intake.submit(new SubmitReport("otra_persona", ReportContentType.PUBLICACION, "1", "SPAM",
                "Engañoso", List.of()));
        Session buyer = login("comprador@example.com");
        Session support = login("agente@example.com");
        String claim = CASES + "/" + created.caseId() + "/claim";

        mvc.perform(get(CASES)).andExpect(status().isUnauthorized());
        mvc.perform(get(CASES).cookie(buyer.cookie())).andExpect(status().isForbidden());
        mvc.perform(get(CASES).cookie(support.cookie())).andExpect(status().isOk());

        mvc.perform(post(claim).cookie(buyer.cookie()).header(buyer.header(), buyer.token()))
                .andExpect(status().isForbidden());
        mvc.perform(post(claim).cookie(support.cookie())).andExpect(status().isForbidden());
        mvc.perform(post(claim).cookie(support.cookie()).header(support.header(), support.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAgentId", is(supportId.toString())));
        assertThat(jdbc.queryForObject("SELECT assigned_agent_id FROM moderation_cases", String.class))
                .isEqualTo(supportId.toString());
    }

    @Test
    void decisionsAreAuditedWithTheAccountIdAndNeedTheCsrfToken() throws Exception {
        Intake created = intake.submit(new SubmitReport("otra_persona", ReportContentType.PUBLICACION, "1", "SPAM",
                "Engañoso", List.of()));
        Session support = login("agente@example.com");
        String url = CASES + "/" + created.caseId() + "/decisions";
        String body = "{\"decision\":\"MANTENER\",\"justification\":\"Se revisó y cumple las normas.\"}";

        mvc.perform(post(url).cookie(support.cookie()).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).cookie(support.cookie()).header(support.header(), support.token())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT agent_id FROM moderation_actions", String.class))
                .isEqualTo(supportId.toString());
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_logs WHERE action='MODERATION_DECISION'",
                String.class)).isEqualTo(supportId.toString());
    }

    @Test
    void onlyConfiguredOriginsMayUseTheSessionCookieFromABrowser() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:4300")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "x-csrf-token,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4300"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/api/auth/login").header("Origin", "http://sitio-malicioso.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private Session login(String email) throws Exception {
        Session anonymous = csrf(null);
        MvcResult result = mvc.perform(post("/api/auth/login").cookie(anonymous.cookie())
                        .contentType("application/x-www-form-urlencoded")
                        .param("email", email).param("password", PASSWORD)
                        .header(anonymous.header(), anonymous.token()))
                .andExpect(status().isNoContent()).andReturn();
        return csrf(result.getResponse().getCookie("SESSION"));
    }

    private Session csrf(Cookie cookie) throws Exception {
        var request = get("/api/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        Cookie issued = result.getResponse().getCookie("SESSION");
        return new Session(issued == null ? cookie : issued, body.get("headerName").asText(),
                body.get("token").asText());
    }

    private record Session(Cookie cookie, String header, String token) {
    }
}
