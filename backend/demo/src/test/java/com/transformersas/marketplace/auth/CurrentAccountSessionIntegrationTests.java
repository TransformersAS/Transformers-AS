package com.transformersas.marketplace.auth;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Persistence changes happen after real login; every following request restores its identity from JDBC. */
class CurrentAccountSessionIntegrationTests extends AbstractIntegrationTest {
    @Autowired JdbcIndexedSessionRepository sessions;
    private long account;

    @BeforeEach
    void accounts() {
        account = createAccount("changing@example.com", "COMPRADOR", "VENDEDOR");
        createAccount("unaffected@example.com", "COMPRADOR");
    }

    private Session authenticate(String email, boolean persistent) throws Exception {
        var anonymous = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readTree(anonymous.getContentAsString());
        var login = mvc.perform(post("/api/auth/login").cookie(anonymous.getCookie("SESSION"))
                .param("email", email).param("password", PASSWORD).param("rememberMe", String.valueOf(persistent))
                .header(csrf.get("headerName").asString(), csrf.get("token").asString()))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        var cookie = login.getCookie("SESSION");
        assertThat(cookie.getMaxAge()).isEqualTo(persistent ? 604800 : -1);
        var response = mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse();
        var token = json.readTree(response.getContentAsString());
        return new Session(cookie, token.get("headerName").asString(), token.get("token").asString());
    }

    private Session buyer(boolean persistent) throws Exception {
        Session session = authenticate("changing@example.com", persistent);
        select(session, "COMPRADOR", 200);
        return session;
    }

    private void select(Session session, String role, int expectedStatus) throws Exception {
        perform(session, put("/api/auth/active-role").contentType("application/json")
                .content("{\"role\":\"" + role + "\"}")).andExpect(status().is(expectedStatus));
    }

    private void remove(String role) {
        jdbc.update("DELETE FROM user_account_roles WHERE account_id=? AND role=?", account, role);
    }

    private String id(Session session) {
        return new String(Base64.getDecoder().decode(session.cookie().getValue()), StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deactivationRejectsNextReadAndWriteInvalidatesSessionsAndDoesNotAffectOtherAccount(boolean persistent) throws Exception {
        Session first = buyer(persistent);
        Session second = buyer(persistent);
        Session unaffected = authenticate("unaffected@example.com", persistent);
        jdbc.update("UPDATE user_accounts SET status='INACTIVA' WHERE id=?", account);
        var rejected = perform(first, get("/api/auth/me")).andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(rejected.getCookie("SESSION").getMaxAge()).isZero();
        perform(second, post("/api/cart/items").contentType("application/json")
                .content("{\"productId\":1,\"quantity\":1}")).andExpect(status().isUnauthorized());
        assertThat(sessions.findById(id(first))).isNull();
        assertThat(sessions.findById(id(second))).isNull();
        assertThat(count("cart_items")).isZero();
        perform(unaffected, get("/api/auth/validation/comprador")).andExpect(status().isNoContent());
        perform(unaffected, get("/api/auth/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("COMPRADOR"));
        // Reactivating the account must not restore either invalidated browser credential.
        jdbc.update("UPDATE user_accounts SET status='ACTIVA' WHERE id=?", account);
        perform(first, get("/api/auth/me")).andExpect(status().isUnauthorized());
        perform(second, get("/api/auth/me")).andExpect(status().isUnauthorized());
        perform(unaffected, get("/api/auth/me")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removingActiveRoleImmediatelyDeniesItAndNeverAutoSelectsTheRemainingRole(boolean persistent) throws Exception {
        Session session = buyer(persistent);
        remove("COMPRADOR");
        perform(session, get("/api/cart")).andExpect(status().isForbidden());
        perform(session, get("/api/auth/validation/comprador")).andExpect(status().isForbidden());
        perform(session, get("/api/auth/validation/vendedor")).andExpect(status().isForbidden());
        perform(session, get("/api/auth/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("VENDEDOR"))
                .andExpect(jsonPath("$.activeRole").isEmpty());
        select(session, "COMPRADOR", 403);
        select(session, "VENDEDOR", 200);
        perform(session, get("/api/auth/validation/vendedor")).andExpect(status().isNoContent());
        perform(session, get("/api/cart")).andExpect(status().isForbidden());
        assertSafeStoredSession(session, persistent);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roleSelectionAsFirstRequestAfterRemovalCannotUseTheOldRoleSet(boolean persistent) throws Exception {
        Session session = buyer(persistent);
        remove("VENDEDOR");
        select(session, "VENDEDOR", 403);
        perform(session, get("/api/auth/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("COMPRADOR"))
                .andExpect(jsonPath("$.activeRole").value("COMPRADOR"));
        perform(session, get("/api/auth/validation/comprador")).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void meAsFirstRequestReflectsCurrentRolesAndNoRoleIsSelectedEvenIfOnlyOneRemains(boolean persistent) throws Exception {
        Session session = authenticate("changing@example.com", persistent); // multiple roles, no selection
        remove("VENDEDOR");
        perform(session, get("/api/auth/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("COMPRADOR"))
                .andExpect(jsonPath("$.activeRole").isEmpty());
        perform(session, get("/api/auth/validation/comprador")).andExpect(status().isForbidden());
        select(session, "COMPRADOR", 200);
        perform(session, get("/api/auth/validation/comprador")).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingAllRolesAndReaddingOneDoesNotRestorePermissionsUntilExplicitSelection(boolean persistent) throws Exception {
        Session session = buyer(persistent);
        jdbc.update("DELETE FROM user_account_roles WHERE account_id=?", account);
        perform(session, get("/api/auth/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isEmpty()).andExpect(jsonPath("$.activeRole").isEmpty());
        select(session, "COMPRADOR", 403);
        jdbc.update("INSERT INTO user_account_roles(account_id,role) VALUES (?,'COMPRADOR')", account);
        perform(session, get("/api/auth/validation/comprador")).andExpect(status().isForbidden());
        perform(session, get("/api/auth/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("COMPRADOR"))
                .andExpect(jsonPath("$.activeRole").isEmpty());
        select(session, "COMPRADOR", 200);
        perform(session, get("/api/auth/validation/comprador")).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refreshedSessionsKeepCsrfLogoutAndRevocationProtectionWithoutRecreatingRevokedSessions(boolean persistent) throws Exception {
        Session current = buyer(persistent);
        Session other = buyer(persistent);
        remove("VENDEDOR");
        perform(current, get("/api/auth/me")).andExpect(status().isOk());
        assertSafeStoredSession(current, persistent);
        var listing = json.readTree(perform(current, get("/api/auth/sessions")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String otherPublicId = listing.valueStream().filter(node -> !node.get("current").asBoolean())
                .findFirst().orElseThrow().get("id").asString();
        mvc.perform(put("/api/auth/active-role").cookie(current.cookie()).contentType("application/json")
                .content("{\"role\":\"COMPRADOR\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/auth/sessions/{id}", otherPublicId).cookie(current.cookie())).andExpect(status().isForbidden());
        perform(current, delete("/api/auth/sessions/{id}", otherPublicId)).andExpect(status().isNoContent());
        perform(other, get("/api/auth/me")).andExpect(status().isUnauthorized());
        assertThat(sessions.findById(id(other))).isNull();
        mvc.perform(post("/api/auth/logout").cookie(current.cookie())).andExpect(status().isForbidden());
        perform(current, get("/api/auth/me")).andExpect(status().isOk());
        perform(current, post("/api/auth/logout")).andExpect(status().isNoContent());
        perform(current, get("/api/auth/me")).andExpect(status().isUnauthorized());
        assertThat(sessions.findById(id(current))).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletedAccountAlsoInvalidatesTheExistingSession(boolean persistent) throws Exception {
        Session session = buyer(persistent);
        jdbc.update("DELETE FROM user_account_roles WHERE account_id=?", account);
        jdbc.update("DELETE FROM user_accounts WHERE id=?", account);
        perform(session, get("/api/auth/me")).andExpect(status().isUnauthorized());
        assertThat(sessions.findById(id(session))).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removalAlsoAppliesToEveryRoleIncludingAdminAndSupport(boolean persistent) throws Exception {
        for (String role : new String[]{"COMPRADOR", "VENDEDOR", "ADMIN", "SOPORTE"}) {
            String email = role.toLowerCase() + "@example.com";
            long owner = createAccount(email, role);
            Session session = authenticate(email, persistent);
            String path = switch (role) {
                case "COMPRADOR" -> "/api/auth/validation/comprador";
                case "VENDEDOR" -> "/api/auth/validation/vendedor";
                case "ADMIN" -> "/api/admin/categories";
                default -> "/api/support/moderation/cases";
            };
            perform(session, get(path)).andExpect(status().is(role.equals("COMPRADOR") || role.equals("VENDEDOR") ? 204 : 200));
            jdbc.update("DELETE FROM user_account_roles WHERE account_id=? AND role=?", owner, role);
            perform(session, get(path)).andExpect(status().isForbidden());
            perform(session, get("/api/auth/me")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles").isEmpty()).andExpect(jsonPath("$.activeRole").isEmpty());
            select(session, role, 403);
        }
    }

    private void assertSafeStoredSession(Session session, boolean persistent) {
        org.springframework.session.Session stored = sessions.findById(id(session));
        assertThat(stored).isNotNull();
        assertThat(stored.getMaxInactiveInterval()).isEqualTo(Duration.ofSeconds(persistent ? 604800 : 1800));
        SecurityContext context = stored.getAttribute("SPRING_SECURITY_CONTEXT");
        AccountPrincipal principal = (AccountPrincipal) context.getAuthentication().getPrincipal();
        assertThat(principal.getPassword()).isNull();
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(principal.roles().stream().map(Enum::name).toList()).containsExactlyInAnyOrderElementsOf(
                jdbc.queryForList("SELECT role FROM user_account_roles WHERE account_id=?", String.class, account));
        assertThat(context.getAuthentication().getAuthorities()).extracting("authority")
                .containsExactly("ROLE_" + principal.activeRole().name());
    }
}
