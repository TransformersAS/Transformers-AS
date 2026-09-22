package com.transformersas.marketplace.auth;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real SMTP to a loopback mailbox, MySQL, Spring Security and JDBC sessions. No external recipient. */
@ExtendWith(OutputCaptureExtension.class)
class PasswordRecoverySmtpIntegrationTests extends AbstractIntegrationTest {
    private static final String EMAIL = "recovery@example.com";
    private static final String NEW_PASSWORD = "RecoveredPassword!123";
    private static final String REQUEST = "/api/auth/password-recovery/request";
    private static final String CONFIRM = "/api/auth/password-recovery/confirm";
    private static final GreenMail mail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
    static { mail.start(); }
    @Autowired JavaMailSender sender;
    @Autowired PasswordEncoder encoder;
    private long account;

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", () -> mail.getSmtp().getPort());
        properties.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        properties.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        properties.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
        properties.add("app.password-recovery.from", () -> "recovery@marketplace.test");
        properties.add("app.email-verification.from", () -> "verification@marketplace.test");
    }

    @BeforeEach
    void mailboxAndAccount() throws Exception {
        mail.purgeEmailFromAllMailboxes();
        account = createAccount(EMAIL, "COMPRADOR");
    }

    @AfterAll
    static void stopMail() { mail.stop(); }

    private ResultActions publicPost(String path, Map<String, String> body) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var token = json.readTree(response.getContentAsString());
        return mvc.perform(post(path).cookie(response.getCookie("SESSION"))
                .header(token.get("headerName").asString(), token.get("token").asString())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private String request(String email) throws Exception {
        return publicPost(REQUEST, Map.of("email", email)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.token").doesNotExist()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions confirm(String token) throws Exception {
        return publicPost(CONFIRM, Map.of("token", token, "newPassword", NEW_PASSWORD));
    }

    private String lastToken() throws Exception {
        var messages = mail.getReceivedMessages();
        var message = messages[messages.length - 1];
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        var match = Pattern.compile("(?m)^[A-Za-z0-9_-]{43}\\r?$").matcher(message.getContent().toString());
        assertThat(match.find()).isTrue();
        return match.group().strip();
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void realMailContainsUsableTokenOnlyHashIsStoredAndConfirmationChangesPasswordAndRevokesSessions(CapturedOutput output) throws Exception {
        Session previous = login(EMAIL);
        String response = request(" RECOVERY@example.com ");
        assertThat(mail.getReceivedMessages()).hasSize(1);
        var message = mail.getReceivedMessages()[0];
        assertThat(message.getFrom()[0].toString()).isEqualTo("recovery@marketplace.test");
        assertThat(message.getSubject()).isEqualTo("Recupera tu contraseña — Marketplace");
        assertThat(message.getContent().toString()).contains("15 minutos", "Ya tengo un token de recuperación");
        String token = lastToken();
        assertThat(response).doesNotContain(token, EMAIL);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_recovery_tokens", String.class))
                .isEqualTo(hash(token)).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,created_at,expires_at) FROM password_recovery_tokens", Long.class))
                .isEqualTo(900L);
        assertThat(jdbc.queryForMap("SELECT * FROM password_recovery_tokens").values()).doesNotContain(token);
        assertThat(count("email_verification_tokens")).isZero();
        confirm(token).andExpect(status().isNoContent());
        String storedPassword = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, account);
        assertThat(encoder.matches(NEW_PASSWORD, storedPassword)).isTrue();
        assertThat(encoder.matches(PASSWORD, storedPassword)).isFalse();
        perform(previous, get("/api/auth/me")).andExpect(status().isUnauthorized());
        attemptLogin(PASSWORD).andExpect(status().isUnauthorized());
        attemptLogin(NEW_PASSWORD).andExpect(status().isNoContent());
        confirm(token).andExpect(status().isBadRequest());
        assertThat(output.getAll()).doesNotContain(token, NEW_PASSWORD);
    }

    private ResultActions attemptLogin(String password) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var token = json.readTree(response.getContentAsString());
        return mvc.perform(post("/api/auth/login").cookie(response.getCookie("SESSION"))
                .header(token.get("headerName").asString(), token.get("token").asString())
                .param("email", EMAIL).param("password", password));
    }

    @Test
    void existingMissingInactiveAndMalformedEmailsHaveSameResponseWithoutSendingToUnknownAccounts() throws Exception {
        createAccount("inactive@example.com", "COMPRADOR");
        jdbc.update("UPDATE user_accounts SET status='INACTIVA' WHERE email='inactive@example.com'");
        String response = request(EMAIL);
        for (String email : new String[]{"missing@example.com", "inactive@example.com", "", "not-an-email"}) {
            assertThat(request(email)).isEqualTo(response);
        }
        assertThat(mail.getReceivedMessages()).hasSize(1);
        assertThat(count("password_recovery_tokens")).isEqualTo(1);
    }

    @Test
    void smtpFailureRemainsGenericCommitsOnlyHashAndAllowsRetryWithoutLoggingSecrets(CapturedOutput output) throws Exception {
        String deliveredResponse = request(EMAIL);
        String superseded = lastToken();
        var smtp = (JavaMailSenderImpl) sender;
        int originalPort = smtp.getPort();
        int closedPort;
        try (var socket = new java.net.ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        try {
            smtp.setPort(closedPort);
            assertThat(request(EMAIL)).isEqualTo(deliveredResponse);
            assertThat(request("missing@example.com")).isEqualTo(deliveredResponse);
        } finally { smtp.setPort(originalPort); }
        assertThat(mail.getReceivedMessages()).hasSize(1);
        assertThat(count("password_recovery_tokens")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_recovery_tokens WHERE used_at IS NULL", String.class))
                .matches("[a-f0-9]{64}").isNotEqualTo(hash(superseded));
        assertThat(encoder.matches(PASSWORD, jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, account))).isTrue();
        confirm(superseded).andExpect(status().isBadRequest());
        assertThat(output.getAll()).contains("Password recovery notification could not be delivered")
                .doesNotContain(superseded, "MailSendException", "Failed messages:");
        request(EMAIL);
        confirm(lastToken()).andExpect(status().isNoContent());
    }

    @Test
    void invalidExpiredAndReplacedTokensRemainRejectedAndUsedTokenCannotResetAgain() throws Exception {
        request(EMAIL);
        String expired = lastToken();
        jdbc.update("UPDATE password_recovery_tokens SET expires_at=CURRENT_TIMESTAMP - INTERVAL 1 MINUTE");
        confirm(expired).andExpect(status().isBadRequest());
        confirm("bad-token").andExpect(status().isBadRequest());
        confirm("A".repeat(43)).andExpect(status().isBadRequest());
        request(EMAIL);
        String replaced = lastToken();
        request(EMAIL);
        String usable = lastToken();
        assertThat(usable).isNotEqualTo(replaced).isNotEqualTo(expired);
        confirm(replaced).andExpect(status().isBadRequest());
        confirm(usable).andExpect(status().isNoContent());
        confirm(usable).andExpect(status().isBadRequest());
    }

    @Test
    void verificationAndRecoveryUseSeparateTokensSendersAndConfirmationEndpoints() throws Exception {
        jdbc.update("UPDATE user_accounts SET email_verified_at=NULL WHERE id=?", account);
        publicPost("/api/auth/email-verification/resend", Map.of("email", EMAIL, "password", PASSWORD))
                .andExpect(status().isAccepted());
        String verification = lastToken();
        assertThat(mail.getReceivedMessages()[0].getFrom()[0].toString()).isEqualTo("verification@marketplace.test");
        request(EMAIL);
        String recovery = lastToken();
        assertThat(recovery).isNotEqualTo(verification);
        assertThat(mail.getReceivedMessages()[1].getFrom()[0].toString()).isEqualTo("recovery@marketplace.test");
        confirm(verification).andExpect(status().isBadRequest());
        publicPost("/api/auth/email-verification/confirm", Map.of("token", recovery)).andExpect(status().isBadRequest());
        confirm(recovery).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT email_verified_at FROM user_accounts WHERE id=?", java.sql.Timestamp.class, account)).isNull();
        publicPost("/api/auth/email-verification/confirm", Map.of("token", verification)).andExpect(status().isNoContent());
    }
}
