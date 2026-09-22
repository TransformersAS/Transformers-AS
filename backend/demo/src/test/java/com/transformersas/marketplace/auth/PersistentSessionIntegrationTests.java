package com.transformersas.marketplace.auth;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses real Spring Security filters, cookies, CSRF and Spring Session JDBC on MySQL. */
class PersistentSessionIntegrationTests extends AbstractIntegrationTest {
    private static final String EMAIL = "remember@example.com";
    private static final int NORMAL_SECONDS = 1800;
    private static final int PERSISTENT_SECONDS = 604800;
    @Autowired JdbcIndexedSessionRepository sessions;
    private Long accountId;

    @BeforeEach
    void account() { accountId = createAccount(EMAIL, "COMPRADOR"); }

    private Session csrf(Cookie cookie) throws Exception {
        var request = get("/api/auth/csrf");
        if (cookie != null) request.cookie(cookie);
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        var body = json.readTree(response.getContentAsString());
        Cookie issued = response.getCookie("SESSION");
        return new Session(issued == null ? cookie : issued, body.get("headerName").asString(), body.get("token").asString());
    }

    private MvcResult login(Session csrf, String remember, boolean secure) throws Exception {
        var request = post("/api/auth/login").secure(secure).param("email", EMAIL).param("password", PASSWORD);
        if (remember != null) request.param("rememberMe", remember);
        return mvc.perform(csrf.apply(request)).andExpect(status().isNoContent()).andReturn();
    }

    private Cookie login(boolean remember) throws Exception {
        return login(csrf(null), String.valueOf(remember), false).getResponse().getCookie("SESSION");
    }

    private String id(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }

    private void assertStoredTimeout(Cookie cookie, int seconds) {
        org.springframework.session.Session stored = sessions.findById(id(cookie));
        assertThat(stored).isNotNull();
        assertThat(stored.getMaxInactiveInterval()).isEqualTo(Duration.ofSeconds(seconds));
        assertThat(jdbc.queryForObject("SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE SESSION_ID=?",
                Integer.class, id(cookie))).isEqualTo(seconds);
        assertThat(jdbc.queryForObject("SELECT EXPIRY_TIME - LAST_ACCESS_TIME FROM SPRING_SESSION WHERE SESSION_ID=?",
                Long.class, id(cookie))).isEqualTo(seconds * 1000L);
    }

    /** Simulate elapsed inactivity without sleeping or changing application clocks. */
    private void age(Cookie cookie, Duration idle) {
        long lastAccess = Instant.now().minus(idle).toEpochMilli();
        jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=?, EXPIRY_TIME=? + MAX_INACTIVE_INTERVAL * 1000 WHERE SESSION_ID=?",
                lastAccess, lastAccess, id(cookie));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false", "on", "1", "invalid"})
    void normalOrUnrecognizedPreferenceKeepsSessionCookieAndThirtyMinuteTimeout(String remember) throws Exception {
        Cookie cookie = login(csrf(null), remember, false).getResponse().getCookie("SESSION");
        assertThat(cookie.getMaxAge()).isEqualTo(-1);
        assertThat(cookie.getAttribute("Expires")).isNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertStoredTimeout(cookie, NORMAL_SECONDS);
    }

    @Test
    void optedInLoginPersistsCookieAndJdbcTimeoutWithoutWeakeningCookieOrSessionFixationProtection() throws Exception {
        Session anonymous = csrf(null);
        var result = login(anonymous, "true", true);
        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertThat(cookie.getMaxAge()).isEqualTo(PERSISTENT_SECONDS);
        assertThat(cookie.getAttribute("Expires")).isNotBlank();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(result.getResponse().getCookies()).extracting(Cookie::getName).containsExactly("SESSION");
        assertThat(cookie.getValue()).isNotEqualTo(anonymous.cookie().getValue());
        assertStoredTimeout(cookie, PERSISTENT_SECONDS);
        mvc.perform(get("/api/auth/me").cookie(anonymous.cookie())).andExpect(status().isUnauthorized());
        byte[] context = jdbc.queryForObject("""
                SELECT a.ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES a
                JOIN SPRING_SESSION s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID
                WHERE s.SESSION_ID = ? AND a.ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'
                """, byte[].class, id(cookie));
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, accountId);
        assertThat(new String(context, StandardCharsets.ISO_8859_1)).doesNotContain(PASSWORD, passwordHash);
    }

    @Test
    void onlyPersistentSessionSurvivesThirtyOneMinutesOfInactivity() throws Exception {
        Cookie normal = login(false);
        Cookie persistent = login(true);
        age(normal, Duration.ofMinutes(31));
        age(persistent, Duration.ofMinutes(31));
        mvc.perform(get("/api/auth/me").cookie(normal)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(persistent)).andExpect(status().isOk());
        assertStoredTimeout(persistent, PERSISTENT_SECONDS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothModesRestoreIdentityFromOnlyCookieAndJdbc(boolean persistent) throws Exception {
        Cookie issued = login(persistent);
        // Fresh HTTP request: no MockHttpSession or injected Authentication, only the browser credential.
        Cookie restored = new Cookie("SESSION", issued.getValue());
        mvc.perform(get("/api/auth/me").cookie(restored)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.activeRole").value("COMPRADOR"));
        assertStoredTimeout(restored, persistent ? PERSISTENT_SECONDS : NORMAL_SECONDS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothModesExpireOnServerEvenIfClientReplaysCookie(boolean persistent) throws Exception {
        Cookie cookie = login(persistent);
        age(cookie, Duration.ofSeconds((persistent ? PERSISTENT_SECONDS : NORMAL_SECONDS) + 60L));
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void logoutRequiresCsrfDeletesCookieAndJdbcSessionInBothModes(boolean persistent) throws Exception {
        Cookie cookie = login(persistent);
        mvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk());
        var response = mvc.perform(csrf(cookie).apply(post("/api/auth/logout")))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        Cookie deleted = response.getCookie("SESSION");
        assertThat(deleted.getMaxAge()).isZero();
        assertThat(deleted.getValue()).isEmpty();
        assertThat(sessions.findById(id(cookie))).isNull();
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void newNormalLoginDoesNotInheritPersistenceAndOtherBrowsersAreUnaffected() throws Exception {
        Cookie persistent = login(true);
        Cookie normal = login(false);
        assertThat(normal.getMaxAge()).isEqualTo(-1);
        assertStoredTimeout(normal, NORMAL_SECONDS);
        assertStoredTimeout(persistent, PERSISTENT_SECONDS);
        // Reauthenticate in the persistent browser, this time omitting the preference.
        Cookie downgraded = login(csrf(persistent), null, false).getResponse().getCookie("SESSION");
        assertThat(downgraded.getMaxAge()).isEqualTo(-1);
        assertStoredTimeout(downgraded, NORMAL_SECONDS);
        mvc.perform(get("/api/auth/me").cookie(persistent)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(normal)).andExpect(status().isOk());
    }

    @Test
    void preferenceCannotExtendAnonymousSessionOrBypassCsrf() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf").param("rememberMe", "true"))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie anonymous = response.getCookie("SESSION");
        assertThat(anonymous.getMaxAge()).isEqualTo(-1);
        mvc.perform(post("/api/auth/login").cookie(anonymous).param("email", EMAIL).param("password", PASSWORD)
                .param("rememberMe", "true")).andExpect(status().isForbidden());
        assertStoredTimeout(anonymous, NORMAL_SECONDS);
        mvc.perform(get("/api/auth/me").cookie(anonymous)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", "missing-account", "inactive", "unverified"})
    void failedLoginCannotIssuePersistentAuthenticatedSession(String scenario) throws Exception {
        if (scenario.equals("inactive")) jdbc.update("UPDATE user_accounts SET status='INACTIVA' WHERE id=?", accountId);
        if (scenario.equals("unverified")) jdbc.update("UPDATE user_accounts SET email_verified_at=NULL WHERE id=?", accountId);
        Session anonymous = csrf(null);
        var request = post("/api/auth/login").param("rememberMe", "true")
                .param("email", scenario.equals("missing-account") ? "missing@example.com" : EMAIL)
                .param("password", scenario.equals("wrong-password") ? "WrongPassword123!" : PASSWORD);
        mvc.perform(anonymous.apply(request)).andExpect(status().is(scenario.equals("unverified") ? 403 : 401));
        assertStoredTimeout(anonymous.cookie(), NORMAL_SECONDS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT'",
                Integer.class)).isZero();
        mvc.perform(get("/api/auth/me").cookie(anonymous.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void persistenceDoesNotGrantAdditionalRolesOrAllowWritesWithoutCsrf() throws Exception {
        Cookie cookie = login(true);
        mvc.perform(get("/api/auth/validation/vendedor").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(put("/api/auth/active-role").cookie(cookie).contentType("application/json")
                .content("{\"role\":\"COMPRADOR\"}")).andExpect(status().isForbidden());
        mvc.perform(csrf(cookie).apply(put("/api/auth/active-role").contentType("application/json")
                .content("{\"role\":\"VENDEDOR\"}"))).andExpect(status().isForbidden());
        assertStoredTimeout(cookie, PERSISTENT_SECONDS);
    }

    @Test
    void revocationStillInvalidatesPersistentSession() throws Exception {
        Cookie current = login(false);
        Cookie other = login(true);
        String publicId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(id(other).getBytes(StandardCharsets.UTF_8)));
        mvc.perform(csrf(current).apply(delete("/api/auth/sessions/{id}", publicId))).andExpect(status().isNoContent());
        assertThat(sessions.findById(id(other))).isNull();
        mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(current)).andExpect(status().isOk());
    }

    @Test
    void passwordChangeRevokesOtherPersistentSessionsAndKeepsCurrentPolicy() throws Exception {
        Cookie current = login(true);
        Cookie other = login(true);
        mvc.perform(csrf(current).apply(put("/api/auth/password").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("currentPassword", PASSWORD, "newPassword", "NewPassword123!")))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(other)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(current)).andExpect(status().isOk());
        assertStoredTimeout(current, PERSISTENT_SECONDS);
    }
}
