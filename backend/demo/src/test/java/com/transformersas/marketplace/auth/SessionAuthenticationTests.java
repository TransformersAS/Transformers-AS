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
