package com.transformersas.marketplace.auth;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL, cookies/CSRF and SMTP to an isolated local mailbox; no external email is sent. */
class EmailVerificationIntegrationTests extends AbstractIntegrationTest {
    private static final GreenMail mail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
    static { mail.start(); }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", () -> mail.getSmtp().getPort());
        properties.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        properties.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        properties.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
        properties.add("app.email-verification.from", () -> "verification@marketplace.test");
    }

    @Autowired JavaMailSender sender;

    @BeforeEach
    void pendingAccount() throws Exception {
        mail.purgeEmailFromAllMailboxes();
        createAccount("pending@example.com", "COMPRADOR");
        jdbc.update("UPDATE user_accounts SET email_verified_at = NULL WHERE email = 'pending@example.com'");
    }

    @AfterAll
    static void closeMail() { mail.stop(); }

    private record Anonymous(Cookie cookie, String header, String token) {}

    private Anonymous anonymous() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var body = json.readTree(response.getContentAsString());
        return new Anonymous(response.getCookie("SESSION"), body.get("headerName").asString(), body.get("token").asString());
    }

    private ResultActions publicPost(String path, Object body) throws Exception {
        var csrf = anonymous();
        return mvc.perform(post(path).cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private ResultActions attemptLogin(String email, String password) throws Exception {
        var csrf = anonymous();
        return mvc.perform(post("/api/auth/login").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                .param("email", email).param("password", password));
    }

    private ResultActions resend(String email, String password) throws Exception {
        return publicPost("/api/auth/email-verification/resend", Map.of("email", email, "password", password));
    }

    private ResultActions confirm(String token) throws Exception {
        return publicPost("/api/auth/email-verification/confirm", Map.of("token", token));
    }

    private String latestToken() throws Exception {
        var messages = mail.getReceivedMessages();
        var message = messages[messages.length - 1];
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("pending@example.com");
        assertThat(message.getFrom()[0].toString()).isEqualTo("verification@marketplace.test");
        var match = Pattern.compile("(?m)^[A-Za-z0-9_-]{43}\\r?$").matcher(message.getContent().toString());
        assertThat(match.find()).isTrue();
        return match.group().strip();
    }

    @Test
    void loginOnlyRevealsUnverifiedStatusAfterCorrectCredentialsAndCreatesNoAuthenticatedSession() throws Exception {
        attemptLogin("pending@example.com", PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.email").doesNotExist());
        var wrong = attemptLogin("pending@example.com", "WrongPassword123!").andExpect(status().isUnauthorized()).andReturn();
        var missing = attemptLogin("missing@example.com", PASSWORD).andExpect(status().isUnauthorized()).andReturn();
        assertThat(wrong.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT'",
                Integer.class)).isZero();
    }

    @Test
    void smtpDeliversSecretOnlyHashIsStoredAndVerificationAllowsLogin() throws Exception {
        var response = resend(" PENDING@example.com ", PASSWORD).andExpect(status().isAccepted()).andReturn().getResponse();
        String token = latestToken();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("SELECT token_hash FROM email_verification_tokens", String.class)).isEqualTo(hash);
        assertThat(response.getContentAsString()).doesNotContain(token);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, created_at, expires_at) FROM email_verification_tokens", Long.class))
                .isEqualTo(1800);
        confirm(token).andExpect(status().isNoContent());
        Session session = login("pending@example.com");
        perform(session, get("/api/auth/me")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM user_accounts WHERE email='pending@example.com'",
                Boolean.class)).isTrue();
        confirm(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
    }

    @Test
    void rejectsMalformedUnknownExpiredAndSupersededTokens() throws Exception {
        resend("pending@example.com", PASSWORD).andExpect(status().isAccepted());
        String previous = latestToken();
        resend("pending@example.com", PASSWORD).andExpect(status().isAccepted());
        String latest = latestToken();
        assertThat(latest).isNotEqualTo(previous);
        for (String token : new String[]{"", "Mi Tienda", "A".repeat(43), previous}) {
            confirm(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
        }
        jdbc.update("UPDATE email_verification_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL 1 MINUTE WHERE used_at IS NULL");
        confirm(latest).andExpect(status().isBadRequest());
        attemptLogin("pending@example.com", PASSWORD).andExpect(status().isForbidden());
        resend("pending@example.com", PASSWORD).andExpect(status().isAccepted());
        confirm(latestToken()).andExpect(status().isNoContent());
        login("pending@example.com");
    }

    @Test
    void resendRequiresCorrectCredentialsAndDoesNotExposeUnknownAccounts() throws Exception {
        var wrong = resend("pending@example.com", "WrongPassword123!").andExpect(status().isUnauthorized()).andReturn();
        var missing = resend("missing@example.com", PASSWORD).andExpect(status().isUnauthorized()).andReturn();
        assertThat(wrong.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(mail.getReceivedMessages()).isEmpty();
        assertThat(count("email_verification_tokens")).isZero();
    }

    @Test
    void smtpFailureReturnsUnavailableKeepsAccountPendingAndCanBeRetried() throws Exception {
        var smtp = (JavaMailSenderImpl) sender;
        int originalPort = smtp.getPort();
        int closedPort;
        try (var socket = new java.net.ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        try {
            smtp.setPort(closedPort);
            var result = resend("pending@example.com", PASSWORD).andExpect(status().isServiceUnavailable()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD, "pending@example.com", "MailSendException");
            attemptLogin("pending@example.com", PASSWORD).andExpect(status().isForbidden());
            assertThat(count("user_accounts")).isEqualTo(1);
            assertThat(mail.getReceivedMessages()).isEmpty();
        } finally { smtp.setPort(originalPort); }
        resend("pending@example.com", PASSWORD).andExpect(status().isAccepted());
        confirm(latestToken()).andExpect(status().isNoContent());
        login("pending@example.com");
    }

    @Test
    void verificationEndpointsRequireCsrf() throws Exception {
        mvc.perform(post("/api/auth/email-verification/resend").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", "pending@example.com", "password", PASSWORD))))
                .andExpect(status().isForbidden());
        resend("pending@example.com", PASSWORD).andExpect(status().isAccepted());
        String token = latestToken();
        mvc.perform(post("/api/auth/email-verification/confirm").contentType("application/json")
                .content(json.writeValueAsString(Map.of("token", token)))).andExpect(status().isForbidden());
        confirm(token).andExpect(status().isNoContent());
    }

    @Test
    void inactiveAccountCannotVerifyOrResend() throws Exception {
        resend("pending@example.com", PASSWORD).andExpect(status().isAccepted());
        String token = latestToken();
        jdbc.update("UPDATE user_accounts SET status='INACTIVA' WHERE email='pending@example.com'");
        confirm(token).andExpect(status().isBadRequest());
        resend("pending@example.com", PASSWORD).andExpect(status().isUnauthorized());
        attemptLogin("pending@example.com", PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void alreadyVerifiedAccountDoesNotReceiveAnotherToken() throws Exception {
        resend("pending@example.com", PASSWORD).andExpect(status().isAccepted());
        confirm(latestToken()).andExpect(status().isNoContent());
        resend("pending@example.com", PASSWORD).andExpect(status().isAccepted());
        assertThat(mail.getReceivedMessages()).hasSize(1);
        assertThat(count("email_verification_tokens")).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsConsumeTokenOnce() throws Exception {
        resend("pending@example.com", PASSWORD).andExpect(status().isAccepted());
        String token = latestToken();
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> confirmation = () -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return confirm(token).andReturn().getResponse().getStatus();
            };
            var first = workers.submit(confirmation);
            var second = workers.submit(confirmation);
            assertThat(java.util.List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(204, 400);
        }
    }
}
