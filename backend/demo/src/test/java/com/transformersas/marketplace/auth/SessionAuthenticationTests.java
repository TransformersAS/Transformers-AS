package com.transformersas.marketplace.auth;

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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class SessionAuthenticationTests {
    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("session_test").withUsername("test").withPassword("test");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    private String hash;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM SPRING_SESSION");
        jdbc.update("DELETE FROM user_account_roles");
        jdbc.update("DELETE FROM user_accounts");
        hash = encoder.encode("TestPassword!123");
        accounts.save(new UserAccount(null, "person@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
    }

    @Test
    void loginPersistsSessionAndRotatesIdWithoutStoringCredentials() throws Exception {
        Csrf before = csrf(null);
        Cookie authenticated = login(before, " PERSON@example.com ", "TestPassword!123", 204);
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getValue()).isNotEqualTo(before.cookie().getValue());
        assertThat(authenticated.isHttpOnly()).isTrue();
        assertThat(authenticated.getAttribute("SameSite")).isEqualTo("Lax");
        mvc.perform(get("/api/auth/me").cookie(authenticated)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("person@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/api/auth/me").cookie(before.cookie())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME='person@example.com'", Integer.class)).isEqualTo(1);
        byte[] serialized = jdbc.queryForObject("SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT'", byte[].class);
        assertThat(new String(serialized, StandardCharsets.ISO_8859_1)).doesNotContain(hash, "TestPassword!123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", "missing-account", "inactive-account"})
    void invalidCredentialsAreRejectedWithoutAuthenticatedSession(String scenario) throws Exception {
        if (scenario.equals("inactive-account")) jdbc.update("UPDATE user_accounts SET status='INACTIVA'");
        Csrf csrf = csrf(null);
        String email = scenario.equals("missing-account") ? "missing@example.com" : "person@example.com";
        String password = scenario.equals("wrong-password") ? "incorrect" : "TestPassword!123";
        login(csrf, email, password, 401);
        mvc.perform(get("/api/auth/me").cookie(csrf.cookie())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT'", Integer.class)).isZero();
    }

    @Test
    void protectedEndpointsRejectAnonymousRequests() throws Exception {
        for (String path : new String[]{"/api/auth/me", "/api/products", "/api/cart", "/api/addresses"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void logoutDeletesCurrentSessionAndOldCookieCannotAuthenticate() throws Exception {
        Cookie cookie = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Csrf token = csrf(cookie);
        mvc.perform(post("/api/auth/logout").cookie(cookie).header(token.header(), token.token()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME='person@example.com'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT'", Integer.class)).isZero();
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginAndLogoutRequireCsrfAndGetDoesNotLogOut() throws Exception {
        mvc.perform(post("/api/auth/login").param("email", "person@example.com").param("password", "TestPassword!123"))
                .andExpect(status().isForbidden());
        Cookie cookie = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        mvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/logout").cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void logoutLeavesIndependentSessionAlive() throws Exception {
        Cookie first = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Cookie second = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Csrf token = csrf(first);
        mvc.perform(post("/api/auth/logout").cookie(first).header(token.header(), token.token())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void singleRoleIsAutomaticallyActive() throws Exception {
        Cookie cookie = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accounts.findByEmail("person@example.com").orElseThrow().id()))
                .andExpect(jsonPath("$.roles[0]").value("COMPRADOR"))
                .andExpect(jsonPath("$.activeRole").value("COMPRADOR"));
        assertPermissions(cookie, 204, 403);
        assertAuthorities(cookie, "ROLE_COMPRADOR");
    }

    @Test
    void multipleRolesRequireSelectionAndSwitchPermissionsInOnlyThatSession() throws Exception {
        accounts.save(new UserAccount(null, "multi@example.com", hash, AccountStatus.ACTIVA,
                Set.of(Role.COMPRADOR, Role.VENDEDOR)));
        Cookie first = login(csrf(null), "multi@example.com", "TestPassword!123", 204);
        Cookie second = login(csrf(null), "multi@example.com", "TestPassword!123", 204);
        mvc.perform(get("/api/auth/me").cookie(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder("COMPRADOR", "VENDEDOR")))
                .andExpect(jsonPath("$.activeRole").value(org.hamcrest.Matchers.nullValue()));
        assertPermissions(first, 403, 403);
        assertAuthorities(first);
        selectRole(first, "COMPRADOR", 200);
        mvc.perform(get("/api/auth/me").cookie(first)).andExpect(jsonPath("$.activeRole").value("COMPRADOR"));
        assertPermissions(first, 204, 403);
        assertAuthorities(first, "ROLE_COMPRADOR");
        selectRole(first, "VENDEDOR", 200);
        mvc.perform(get("/api/auth/me").cookie(first)).andExpect(jsonPath("$.activeRole").value("VENDEDOR"));
        assertPermissions(first, 403, 204);
        assertAuthorities(first, "ROLE_VENDEDOR");
        assertPermissions(second, 403, 403);
        assertAuthorities(second);
        mvc.perform(get("/api/auth/me").cookie(second))
                .andExpect(jsonPath("$.activeRole").value(org.hamcrest.Matchers.nullValue()));
        assertThat(accounts.findByEmail("multi@example.com").orElseThrow().roles())
                .containsExactlyInAnyOrder(Role.COMPRADOR, Role.VENDEDOR);
    }

    @Test
    void unavailableRoleIsForbiddenAndDoesNotChangeSessionOrAccount() throws Exception {
        Cookie cookie = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        selectRole(cookie, "ADMIN", 403);
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(jsonPath("$.activeRole").value("COMPRADOR"));
        assertPermissions(cookie, 204, 403);
        assertAuthorities(cookie, "ROLE_COMPRADOR");
        assertThat(accounts.findByEmail("person@example.com").orElseThrow().roles()).containsExactly(Role.COMPRADOR);
    }

    @Test
    void roleSelectionRequiresAuthenticationCsrfAndValidRole() throws Exception {
        Csrf anonymous = csrf(null);
        mvc.perform(put("/api/auth/active-role").cookie(anonymous.cookie())
                        .header(anonymous.header(), anonymous.token()).contentType("application/json")
                        .content("{\"role\":\"COMPRADOR\"}"))
                .andExpect(status().isUnauthorized());
        Cookie cookie = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        mvc.perform(put("/api/auth/active-role").cookie(cookie).contentType("application/json")
                        .content("{\"role\":\"COMPRADOR\"}"))
                .andExpect(status().isForbidden());
        selectRole(cookie, "UNKNOWN", 400);
        Csrf token = csrf(cookie);
        mvc.perform(put("/api/auth/active-role").cookie(cookie).header(token.header(), token.token())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertAuthorities(cookie, "ROLE_COMPRADOR");
    }

    @Test
    void listsOwnSessionsAndRevokesOnlyTheSelectedSession() throws Exception {
        Cookie first = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Cookie second = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        accounts.save(new UserAccount(null, "other@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
        Cookie other = login(csrf(null), "other@example.com", "TestPassword!123", 204);
        var result = mvc.perform(get("/api/auth/sessions").cookie(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andReturn();
        var listed = json.readTree(result.getResponse().getContentAsString());
        String currentId = null;
        String otherId = null;
        for (var session : listed) {
            assertThat(session.get("id").asText()).matches("[0-9a-f]{64}");
            var created = java.time.Instant.parse(session.get("createdAt").asText());
            var accessed = java.time.Instant.parse(session.get("lastAccessedAt").asText());
            var expires = java.time.Instant.parse(session.get("expiresAt").asText());
            assertThat(accessed).isAfterOrEqualTo(created);
            assertThat(expires).isAfter(accessed);
            assertThat(session.size()).isEqualTo(5);
            if (session.get("current").asBoolean()) {
                assertThat(currentId).isNull();
                currentId = session.get("id").asText();
            } else otherId = session.get("id").asText();
        }
        assertThat(currentId).isEqualTo(currentManagementId(first));
        assertThat(otherId).isEqualTo(currentManagementId(second));
        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain(first.getValue(), second.getValue(), hash,
                new String(java.util.Base64.getDecoder().decode(first.getValue()), StandardCharsets.UTF_8),
                new String(java.util.Base64.getDecoder().decode(second.getValue()), StandardCharsets.UTF_8));
        revoke(first, otherId, 204);
        mvc.perform(get("/api/auth/me").cookie(second)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(first)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/sessions").cookie(first)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(currentId)).andExpect(jsonPath("$[0].current").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME='person@example.com'", Integer.class)).isEqualTo(1);
    }

    @Test
    void cannotRevokeAnotherAccountsSessionOrAnUnknownSession() throws Exception {
        Cookie own = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        accounts.save(new UserAccount(null, "other@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
        Cookie other = login(csrf(null), "other@example.com", "TestPassword!123", 204);
        revoke(own, currentManagementId(other), 404);
        revoke(own, "unknown", 404);
        mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(own)).andExpect(status().isOk());
    }

    @Test
    void revokingCurrentSessionLogsOutAndLeavesAnotherSessionAlive() throws Exception {
        Cookie current = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Cookie other = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        revoke(current, currentManagementId(current), 204);
        mvc.perform(get("/api/auth/me").cookie(current)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/sessions").cookie(current)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME='person@example.com'", Integer.class)).isEqualTo(1);
    }

    @Test
    void expiredSessionsAreNotListed() throws Exception {
        Cookie current = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Cookie expired = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        String expiredId = new String(java.util.Base64.getDecoder().decode(expired.getValue()), StandardCharsets.UTF_8);
        jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=0, EXPIRY_TIME=0 WHERE SESSION_ID=?", expiredId);
        mvc.perform(get("/api/auth/sessions").cookie(current)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].current").value(true));
        mvc.perform(get("/api/auth/me").cookie(expired)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionManagementRequiresAuthenticationAndRevocationRequiresCsrf() throws Exception {
        mvc.perform(get("/api/auth/sessions")).andExpect(status().isUnauthorized());
        Csrf anonymous = csrf(null);
        mvc.perform(delete("/api/auth/sessions/unknown").cookie(anonymous.cookie())
                        .header(anonymous.header(), anonymous.token())).andExpect(status().isUnauthorized());
        Cookie own = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        mvc.perform(delete("/api/auth/sessions/{id}", currentManagementId(own)).cookie(own))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").cookie(own)).andExpect(status().isOk());
    }

    private String currentManagementId(Cookie cookie) throws Exception {
        var result = mvc.perform(get("/api/auth/sessions").cookie(cookie)).andExpect(status().isOk()).andReturn();
        for (var session : json.readTree(result.getResponse().getContentAsString())) {
            if (session.get("current").asBoolean()) return session.get("id").asText();
        }
        throw new AssertionError("No current session in listing");
    }

    private void revoke(Cookie cookie, String id, int expectedStatus) throws Exception {
        Csrf token = csrf(cookie);
        mvc.perform(delete("/api/auth/sessions/{id}", id).cookie(cookie).header(token.header(), token.token()))
                .andExpect(status().is(expectedStatus));
    }

    private void selectRole(Cookie cookie, String role, int expectedStatus) throws Exception {
        Csrf token = csrf(cookie);
        mvc.perform(put("/api/auth/active-role").cookie(cookie).header(token.header(), token.token())
                        .contentType("application/json").content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private void assertPermissions(Cookie cookie, int comprador, int vendedor) throws Exception {
        mvc.perform(get("/api/auth/validation/comprador").cookie(cookie)).andExpect(status().is(comprador));
        mvc.perform(get("/api/auth/validation/vendedor").cookie(cookie)).andExpect(status().is(vendedor));
    }

    private void assertAuthorities(Cookie cookie, String... expected) {
        String sessionId = new String(java.util.Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
        byte[] serialized = jdbc.queryForObject("""
                SELECT a.ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES a
                JOIN SPRING_SESSION s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID
                WHERE s.SESSION_ID = ? AND a.ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'
                """, byte[].class, sessionId);
        var context = (org.springframework.security.core.context.SecurityContext)
                org.springframework.util.SerializationUtils.deserialize(serialized);
        assertThat(context.getAuthentication().getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactly(expected);
        assertThat(new String(serialized, StandardCharsets.ISO_8859_1)).doesNotContain(hash, "TestPassword!123");
    }

    private Cookie login(Csrf csrf, String email, String password, int status) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").cookie(csrf.cookie())
                        .contentType("application/x-www-form-urlencoded")
                        .param("email", email).param("password", password).header(csrf.header(), csrf.token()))
                .andExpect(status().is(status)).andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    private Csrf csrf(Cookie cookie) throws Exception {
        var request = get("/api/auth/csrf");
        if (cookie != null) request.cookie(cookie);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        Cookie issued = result.getResponse().getCookie("SESSION");
        return new Csrf(issued == null ? cookie : issued, body.get("headerName").asText(), body.get("token").asText());
    }

    private record Csrf(Cookie cookie, String header, String token) {}
}
