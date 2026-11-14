package com.transformersas.marketplace.auth.infrastructure.security;

import com.transformersas.marketplace.auth.infrastructure.persistence.repository.JdbcLoginAttemptRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

/** Tests del guard, no sustituyen el recorrido real Nginx -> RemoteIpValve. */
class LoginAttemptOriginPolicyTests {
    private final JdbcLoginAttemptRepository repository = mock(JdbcLoginAttemptRepository.class);

    private MockEnvironment environment() {
        var env = new MockEnvironment()
                .withProperty("app.auth.login-attempts.enabled", "true")
                .withProperty("app.auth.login-attempts.origin-verified", "true")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("app.auth.login-attempts.trusted-proxy-addresses", "172.30.238.10")
                .withProperty("server.tomcat.remoteip.internal-proxies", "\\Q172.30.238.10\\E");
        env.setActiveProfiles("compose-local");
        return env;
    }

    private Authentication input(LoginAttemptGuard guard, String origin, boolean forwarded, String forged) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(origin);
        if (forwarded) request.setAttribute("org.apache.tomcat.request.forwarded", Boolean.TRUE);
        request.addHeader("X-Forwarded-For", forged);
        request.addHeader("X-Real-IP", forged);
        request.addHeader("Forwarded", "for=" + forged);
        var input = UsernamePasswordAuthenticationToken.unauthenticated(" Person@Example.com ", "unused");
        input.setDetails(guard.details(request));
        return input;
    }

    private Authentication success() {
        return UsernamePasswordAuthenticationToken.authenticated("account", null, List.of());
    }

    private void denied(LoginAttemptGuard guard, String origin, boolean forwarded) {
        assertThatThrownBy(() -> guard.authenticate(input(guard, origin, forwarded, "8.8.8.8"), this::success))
                .isInstanceOfSatisfying(LoginAttemptGuard.Denied.class, e -> assertThat(e.status).isEqualTo(503));
    }

    @Test void localPolicyRequiresExplicitProfileAndExactNumericAddresses() {
        var env = environment().withProperty("app.auth.login-attempts.allowed-local-origins", "127.0.0.1");
        env.setActiveProfiles("demo");
        assertThatThrownBy(() -> new LoginAttemptGuard(env, repository)).isInstanceOf(IllegalArgumentException.class);
        for (String value : List.of("172.30.238.0/24", "localhost", "0.0.0.0", "169.254.1.1", "8.8.8.8", "::")) {
            assertThatThrownBy(() -> new LoginAttemptGuard(environment()
                    .withProperty("app.auth.login-attempts.allowed-local-origins", value), repository))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void privateOriginsStayDeniedByDefaultAndUnlistedNeighboursRemainDenied() {
        denied(new LoginAttemptGuard(environment(), repository), "127.0.0.1", true);
        var guard = new LoginAttemptGuard(environment()
                .withProperty("app.auth.login-attempts.allowed-local-origins", "127.0.0.1,192.168.65.1"), repository);
        denied(guard, "192.168.65.2", true);
        denied(guard, "172.30.238.1", true);
        denied(guard, "127.0.0.1", false);
        verifyNoInteractions(repository);
    }

    @Test void evenExplicitlyListedProxyCannotBecomeClientOrigin() {
        var guard = new LoginAttemptGuard(environment()
                .withProperty("app.auth.login-attempts.allowed-local-origins", "172.30.238.10"), repository);
        denied(guard, "172.30.238.10", true);
        verifyNoInteractions(repository);
    }

    @Test void forgedHeadersCannotSelectCounterKeyAfterTrustedOriginResolution() throws Exception {
        var guard = new LoginAttemptGuard(environment()
                .withProperty("app.auth.login-attempts.allowed-local-origins", "127.0.0.1,::1"), repository);
        when(repository.authenticate(any(byte[].class), any())).thenReturn(
                new JdbcLoginAttemptRepository.Result(success(), null, 0));
        for (String forged : List.of("1.1.1.1", "8.8.8.8", "192.168.65.2, 1.1.1.1")) {
            guard.authenticate(input(guard, "127.0.0.1", true, forged), this::success);
        }
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(
                "person@example.com\0".concat("127.0.0.1").getBytes(StandardCharsets.UTF_8));
        verify(repository, times(3)).authenticate(aryEq(expected), any());
        guard.authenticate(input(guard, "::1", true, "8.8.8.8"), this::success);
    }

    @Test void localOriginStillHonoursPersistedBlock() {
        var guard = new LoginAttemptGuard(environment()
                .withProperty("app.auth.login-attempts.allowed-local-origins", "127.0.0.1"), repository);
        when(repository.authenticate(any(byte[].class), any())).thenReturn(
                new JdbcLoginAttemptRepository.Result(null, null, 900));
        assertThatThrownBy(() -> guard.authenticate(input(guard, "127.0.0.1", true, "8.8.8.8"), this::success))
                .isInstanceOfSatisfying(LoginAttemptGuard.Denied.class, e -> {
                    assertThat(e.status).isEqualTo(429);
                    assertThat(e.retrySeconds).isEqualTo(900);
                });
    }
}
