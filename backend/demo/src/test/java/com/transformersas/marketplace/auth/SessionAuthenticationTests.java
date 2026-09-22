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
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.transformersas.marketplace.auth.application.port.PasswordRecoveryNotifier recoveryNotifier;
    private final java.util.List<String> recoveryTokens = new java.util.ArrayList<>();
    private String hash;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM SPRING_SESSION");
        jdbc.update("DELETE FROM user_account_roles");
        jdbc.update("DELETE FROM user_accounts");
        org.mockito.Mockito.doAnswer(invocation -> {
            recoveryTokens.add(invocation.getArgument(1));
            return null;
        }).when(recoveryNotifier).notifyRecovery(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        hash = encoder.encode("TestPassword!123");
        saveVerified(new UserAccount(null, "person@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
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
        saveVerified(new UserAccount(null, "multi@example.com", hash, AccountStatus.ACTIVA,
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "comprador, COMPRADOR, 204",
            "comprador, VENDEDOR, 403",
            "vendedor, VENDEDOR, 204",
            "vendedor, COMPRADOR, 403"
    })
    void headValidationUsesTheSameActiveRoleAuthorizationAsGet(String endpoint, Role activeRole, int expectedStatus) throws Exception {
        saveVerified(new UserAccount(null, "multi@example.com", hash, AccountStatus.ACTIVA,
                Set.of(Role.COMPRADOR, Role.VENDEDOR)));
        Cookie cookie = login(csrf(null), "multi@example.com", "TestPassword!123", 204);
        selectRole(cookie, activeRole.name(), 200);
        String path = "/api/auth/validation/" + endpoint;
        mvc.perform(head(path).cookie(cookie)).andExpect(status().is(expectedStatus));
        mvc.perform(get(path).cookie(cookie)).andExpect(status().is(expectedStatus));
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
        saveVerified(new UserAccount(null, "other@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
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
        saveVerified(new UserAccount(null, "other@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
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

    @Test
    void passwordChangeStoresBcryptAndRevokesOnlyOtherSessionsOfTheAccount() throws Exception {
        Cookie current = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Cookie second = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Cookie third = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        saveVerified(new UserAccount(null, "other@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.VENDEDOR)));
        Cookie other = login(csrf(null), "other@example.com", "TestPassword!123", 204);
        var before = accounts.findByEmail("person@example.com").orElseThrow();
        changePassword(current, "TestPassword!123", "NewPassword!456", 204);
        var after = accounts.findById(before.id()).orElseThrow();
        assertThat(after.passwordHash()).startsWith("$2").isNotEqualTo(hash).isNotEqualTo("NewPassword!456");
        assertThat(encoder.matches("NewPassword!456", after.passwordHash())).isTrue();
        assertThat(encoder.matches("TestPassword!123", after.passwordHash())).isFalse();
        assertThat(after.email()).isEqualTo(before.email());
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.roles()).isEqualTo(before.roles());
        mvc.perform(get("/api/auth/me").cookie(current)).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRole").value("COMPRADOR"));
        mvc.perform(get("/api/auth/me").cookie(second)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(third)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isOk());
        assertThat(accounts.findByEmail("other@example.com").orElseThrow().passwordHash()).isEqualTo(hash);
        mvc.perform(get("/api/auth/sessions").cookie(current)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].current").value(true));
        login(csrf(null), "person@example.com", "TestPassword!123", 401);
        Cookie fresh = login(csrf(null), "person@example.com", "NewPassword!456", 204);
        mvc.perform(get("/api/auth/me").cookie(fresh)).andExpect(status().isOk());
        for (byte[] bytes : jdbc.query("SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES",
                (rs, row) -> rs.getBytes(1))) {
            assertThat(new String(bytes, StandardCharsets.ISO_8859_1))
                    .doesNotContain("NewPassword!456", "TestPassword!123", after.passwordHash());
        }
    }

    @Test
    void wrongCurrentPasswordDoesNotChangeCredentialsOrRevokeSessions() throws Exception {
        Cookie current = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Cookie other = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        changePassword(current, "WrongPassword!", "NewPassword!456", 403);
        assertThat(accounts.findByEmail("person@example.com").orElseThrow().passwordHash()).isEqualTo(hash);
        mvc.perform(get("/api/auth/me").cookie(current)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isOk());
        login(csrf(null), "person@example.com", "TestPassword!123", 204);
        login(csrf(null), "person@example.com", "NewPassword!456", 401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "            ", "short", "TestPassword!123",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "ééééééééééééééééééééééééééééééééééééé"})
    void invalidNewPasswordDoesNotChangeCredentialsOrRevokeSessions(String password) throws Exception {
        Cookie current = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Cookie other = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        changePassword(current, "TestPassword!123", password, 400);
        assertThat(accounts.findByEmail("person@example.com").orElseThrow().passwordHash()).isEqualTo(hash);
        mvc.perform(get("/api/auth/me").cookie(current)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isOk());
    }

    @Test
    void passwordChangeRequiresAuthenticationCsrfAndBothFields() throws Exception {
        Csrf anonymous = csrf(null);
        mvc.perform(put("/api/auth/password").cookie(anonymous.cookie()).header(anonymous.header(), anonymous.token())
                .contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
        Cookie current = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        mvc.perform(put("/api/auth/password").cookie(current).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        Csrf token = csrf(current);
        for (String body : new String[]{"{}", "{\"currentPassword\":\"TestPassword!123\"}",
                "{\"newPassword\":\"NewPassword!456\"}"}) {
            mvc.perform(put("/api/auth/password").cookie(current).header(token.header(), token.token())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(accounts.findByEmail("person@example.com").orElseThrow().passwordHash()).isEqualTo(hash);
        mvc.perform(get("/api/auth/me").cookie(current)).andExpect(status().isOk());
    }

    @Test
    void recoveryRequestIsGenericAndStoresOnlyHashWithExpiry() throws Exception {
        var existing = requestRecovery(" PERSON@example.com ");
        var missing = requestRecovery("missing@example.com");
        saveVerified(new UserAccount(null, "inactive@example.com", hash, AccountStatus.INACTIVA, Set.of(Role.COMPRADOR)));
        var inactive = requestRecovery("inactive@example.com");
        assertThat(existing.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString())
                .isEqualTo(inactive.getResponse().getContentAsString());
        assertThat(recoveryTokens).hasSize(1);
        String token = recoveryTokens.getFirst();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(existing.getResponse().getContentAsString()).doesNotContain(token);
        String stored = jdbc.queryForObject("SELECT token_hash FROM password_recovery_tokens", String.class);
        assertThat(stored).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)))).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, created_at, expires_at) FROM password_recovery_tokens", Long.class))
                .isEqualTo(900);
        org.mockito.Mockito.verify(recoveryNotifier).notifyRecovery("person@example.com", token);
    }

    @Test
    void recoveryResetsPasswordAndRevokesAllOwnSessionsOnly() throws Exception {
        Cookie first = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        Cookie second = login(csrf(null), "person@example.com", "TestPassword!123", 204);
        saveVerified(new UserAccount(null, "other@example.com", hash, AccountStatus.ACTIVA, Set.of(Role.COMPRADOR)));
        Cookie other = login(csrf(null), "other@example.com", "TestPassword!123", 204);
        requestRecovery("person@example.com");
        String token = recoveryTokens.getFirst();
        confirmRecovery(token, "RecoveredPassword!123", 204);
        var account = accounts.findByEmail("person@example.com").orElseThrow();
        assertThat(account.passwordHash()).startsWith("$2").isNotEqualTo("RecoveredPassword!123");
        assertThat(encoder.matches("RecoveredPassword!123", account.passwordHash())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_recovery_tokens WHERE used_at IS NOT NULL", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(second)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isOk());
        assertThat(accounts.findByEmail("other@example.com").orElseThrow().passwordHash()).isEqualTo(hash);
        login(csrf(null), "person@example.com", "TestPassword!123", 401);
        Cookie fresh = login(csrf(null), "person@example.com", "RecoveredPassword!123", 204);
        mvc.perform(get("/api/auth/me").cookie(fresh)).andExpect(status().isOk());
        confirmRecovery(token, "AnotherPassword!123", 400);
        mvc.perform(get("/api/auth/me").cookie(fresh)).andExpect(status().isOk());
    }

    @Test
    void recoveryRejectsInvalidExpiredAndSupersededTokens() throws Exception {
        requestRecovery("person@example.com");
        String previous = recoveryTokens.getFirst();
        requestRecovery("person@example.com");
        String latest = recoveryTokens.getLast();
        assertThat(latest).isNotEqualTo(previous);
        confirmRecovery(previous, "RecoveredPassword!123", 400);
        confirmRecovery("A".repeat(43), "RecoveredPassword!123", 400);
        confirmRecovery("bad-token", "RecoveredPassword!123", 400);
        jdbc.update("UPDATE password_recovery_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL 1 MINUTE WHERE used_at IS NULL");
        confirmRecovery(latest, "RecoveredPassword!123", 400);
        assertThat(accounts.findByEmail("person@example.com").orElseThrow().passwordHash()).isEqualTo(hash);
    }

    @Test
    void recoveryRejectsWeakPasswordsWithoutConsumingTokenAndRequiresCsrf() throws Exception {
        mvc.perform(post("/api/auth/password-recovery/request").contentType("application/json")
                .content("{\"email\":\"person@example.com\"}")).andExpect(status().isForbidden());
        requestRecovery("person@example.com");
        String token = recoveryTokens.getFirst();
        mvc.perform(post("/api/auth/password-recovery/confirm").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("token", token, "newPassword", "RecoveredPassword!123"))))
                .andExpect(status().isForbidden());
        for (String password : new String[]{"", "            ", "short", "TestPassword!123", "a".repeat(73), "é".repeat(37)}) {
            confirmRecovery(token, password, 400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_recovery_tokens WHERE used_at IS NULL", Integer.class)).isEqualTo(1);
        confirmRecovery(token, "RecoveredPassword!123", 204);
    }

    @Test
    void recoveryRejectsAccountDeactivatedAfterIssuance() throws Exception {
        requestRecovery("person@example.com");
        jdbc.update("UPDATE user_accounts SET status='INACTIVA'");
        confirmRecovery(recoveryTokens.getFirst(), "RecoveredPassword!123", 400);
        assertThat(accounts.findByEmail("person@example.com").orElseThrow().passwordHash()).isEqualTo(hash);
    }

    @Test
    void concurrentConfirmationsConsumeTokenOnlyOnce() throws Exception {
        requestRecovery("person@example.com");
        String token = recoveryTokens.getFirst();
        Csrf first = csrf(null);
        Csrf second = csrf(null);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (Csrf csrf : java.util.List.of(first, second)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mvc.perform(post("/api/auth/password-recovery/confirm").cookie(csrf.cookie())
                            .header(csrf.header(), csrf.token()).contentType("application/json")
                            .content(json.writeValueAsString(java.util.Map.of("token", token, "newPassword", "RecoveredPassword!123"))))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(results.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                    results.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(204, 400);
        } finally { start.countDown(); }
    }

    @Test
    void recoveryDeliveryFailureStillReturnsGenericResponseAndCommitsHashedToken() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("Delivery unavailable"))
                .when(recoveryNotifier).notifyRecovery(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        var existing = requestRecovery("person@example.com");
        var missing = requestRecovery("missing@example.com");
        assertThat(existing.getResponse().getStatus()).isEqualTo(missing.getResponse().getStatus());
        assertThat(existing.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_recovery_tokens WHERE used_at IS NULL", Integer.class)).isEqualTo(1);
        String stored = jdbc.queryForObject("SELECT token_hash FROM password_recovery_tokens", String.class);
        assertThat(stored).matches("[a-f0-9]{64}");
        org.mockito.Mockito.verify(recoveryNotifier).notifyRecovery(org.mockito.ArgumentMatchers.eq("person@example.com"),
                org.mockito.ArgumentMatchers.matches("[A-Za-z0-9_-]{43}"));
        assertThat(accounts.findByEmail("person@example.com").orElseThrow().passwordHash()).isEqualTo(hash);
    }

    private UserAccount saveVerified(UserAccount account) {
        var saved = accounts.save(account);
        accounts.markEmailVerified(saved.id());
        return saved;
    }

    private MvcResult requestRecovery(String email) throws Exception {
        Csrf csrf = csrf(null);
        return mvc.perform(post("/api/auth/password-recovery/request").cookie(csrf.cookie())
                .header(csrf.header(), csrf.token()).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("email", email))))
                .andExpect(status().isAccepted()).andReturn();
    }

    private void confirmRecovery(String token, String password, int status) throws Exception {
        Csrf csrf = csrf(null);
        mvc.perform(post("/api/auth/password-recovery/confirm").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                .contentType("application/json").content(json.writeValueAsString(java.util.Map.of("token", token, "newPassword", password))))
                .andExpect(status().is(status));
    }

    private void changePassword(Cookie cookie, String currentPassword, String newPassword, int expectedStatus) throws Exception {
        Csrf token = csrf(cookie);
        mvc.perform(put("/api/auth/password").cookie(cookie).header(token.header(), token.token())
                .contentType("application/json").content(json.writeValueAsString(java.util.Map.of(
                        "currentPassword", currentPassword, "newPassword", newPassword))))
                .andExpect(status().is(expectedStatus));
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
