package com.transformersas.marketplace.auth.infrastructure.security;

import com.transformersas.marketplace.auth.infrastructure.persistence.repository.JdbcLoginAttemptRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

@Component
public final class LoginAttemptGuard {
    private final boolean enabled;
    private final List<String> trustedProxyAddresses;
    private final List<String> allowedLocalOrigins;
    private final JdbcLoginAttemptRepository attempts;

    public LoginAttemptGuard(Environment environment, JdbcLoginAttemptRepository attempts) {
        this.attempts = attempts;
        enabled = environment.getProperty("app.auth.login-attempts.enabled", Boolean.class, false);
        if (!enabled) { trustedProxyAddresses = List.of(); allowedLocalOrigins = List.of(); return; }
        String localOrigins = environment.getProperty("app.auth.login-attempts.allowed-local-origins", "");
        if (!localOrigins.isBlank() && !Arrays.asList(environment.getActiveProfiles()).contains("compose-local")) {
            throw new IllegalArgumentException("Local login origins require the compose-local profile");
        }
        allowedLocalOrigins = localOrigins.isBlank() ? List.of() : Arrays.stream(localOrigins.split(","))
                .map(String::strip).map(LoginAttemptGuard::literal).map(address -> {
                    if (!(address.isLoopbackAddress() || address.isSiteLocalAddress())
                            || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                        throw new IllegalArgumentException("Only explicit loopback or private local origins are allowed");
                    }
                    return address.getHostAddress();
                }).toList();
        String proxies = environment.getProperty("app.auth.login-attempts.trusted-proxy-addresses", "");
        trustedProxyAddresses = Arrays.stream(proxies.split(",")).map(String::strip)
                .map(LoginAttemptGuard::literal).map(InetAddress::getHostAddress).toList();
        String expected = trustedProxyAddresses.stream().map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b).orElse("");
        if (!environment.getProperty("app.auth.login-attempts.origin-verified", Boolean.class, false)
                || !"native".equals(environment.getProperty("server.forward-headers-strategy"))
                || !expected.equals(environment.getProperty("server.tomcat.remoteip.internal-proxies"))
                || !environment.getProperty("server.tomcat.remoteip.trusted-proxies", "").isBlank()
                || !"X-Forwarded-For".equalsIgnoreCase(environment.getProperty("server.tomcat.remoteip.remote-ip-header", "X-Forwarded-For"))) {
            throw new IllegalArgumentException("Login attempt control requires a verified native proxy configuration");
        }
    }

    /** Captured inside the existing login filter, never from a client-supplied identity header. */
    public Object details(HttpServletRequest request) {
        return enabled ? new OriginDetails(request) : new WebAuthenticationDetails(request);
    }

    public Authentication authenticate(Authentication input, Supplier<Authentication> authenticate) {
        if (!enabled) return authenticate.get();
        if (!(input.getDetails() instanceof OriginDetails details) || !details.forwarded) throw new Denied(503, 0);
        InetAddress address;
        try { address = literal(details.getRemoteAddress()); }
        catch (IllegalArgumentException exception) { throw new Denied(503, 0); }
        if ((!publicOrigin(address) && !allowedLocalOrigins.contains(address.getHostAddress()))
                || trustedProxyAddresses.contains(address.getHostAddress())) throw new Denied(503, 0);
        if (details.failure != null) throw details.failure;
        String identifier = input.getName().strip().toLowerCase(Locale.ROOT);
        byte[] key;
        try {
            key = MessageDigest.getInstance("SHA-256").digest((identifier + "\0" + address.getHostAddress()).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        JdbcLoginAttemptRepository.Result result;
        try { result = attempts.authenticate(key, authenticate); }
        catch (DataAccessException | TransactionException exception) { throw new Denied(503, 0); }
        if (result.retrySeconds() > 0) throw new Denied(429, result.retrySeconds());
        if (result.failure() != null) {
            // ProviderManager may delegate the same failed request to its parent manager.
            details.failure = result.failure();
            throw result.failure();
        }
        return result.authentication();
    }

    private static InetAddress literal(String value) {
        if (value == null || value.isBlank() || value.length() > 45
                || !value.matches("[0-9a-fA-F:.]+") || (!value.contains(":") && !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))) {
            throw new IllegalArgumentException("Numeric address required");
        }
        try { return InetAddress.getByName(value); }
        catch (java.net.UnknownHostException exception) { throw new IllegalArgumentException("Invalid address"); }
    }

    private static boolean publicOrigin(InetAddress address) {
        byte[] b = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        if (b.length == 16) return (b[0] & 0xe0) == 0x20 && !(b[0] == 0x20 && b[1] == 1 && b[2] == 0x0d && (b[3] & 255) == 0xb8);
        int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
        return a != 0 && a < 224 && !(a == 100 && c >= 64 && c <= 127)
                && !(a == 192 && c == 0) && !(a == 198 && (c == 18 || c == 19 || (c == 51 && d == 100)))
                && !(a == 203 && c == 0 && d == 113);
    }

    private static final class OriginDetails extends WebAuthenticationDetails {
        private final boolean forwarded;
        private transient AuthenticationException failure;
        OriginDetails(HttpServletRequest request) {
            super(request);
            forwarded = Boolean.TRUE.equals(request.getAttribute("org.apache.tomcat.request.forwarded"));
        }
    }

    public static final class Denied extends AuthenticationException {
        public final int status;
        public final long retrySeconds;
        Denied(int status, long retrySeconds) { super("Login temporarily unavailable"); this.status = status; this.retrySeconds = retrySeconds; }
    }
}
