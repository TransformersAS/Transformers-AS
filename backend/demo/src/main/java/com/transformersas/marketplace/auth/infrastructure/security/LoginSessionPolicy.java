package com.transformersas.marketplace.auth.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Extends the existing JDBC session, without issuing a second authentication credential. */
public final class LoginSessionPolicy extends DefaultCookieSerializer {
    public static final String DEVICE_DESCRIPTION = LoginSessionPolicy.class.getName() + ".DEVICE_DESCRIPTION";
    public static final String UNKNOWN_DEVICE = "Dispositivo o navegador no identificado";

    /** Untrusted visual hint only: keep a fixed vocabulary, never persist or echo the raw header. */
    private static String describeDevice(String userAgent) {
        if (userAgent == null || userAgent.length() > 512) return UNKNOWN_DEVICE;
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String browser = ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/") ? "Edge"
                : ua.contains("opr/") || ua.contains("opios/") ? "Opera"
                : ua.contains("firefox/") || ua.contains("fxios/") ? "Firefox"
                : ua.contains("chrome/") || ua.contains("crios/") ? "Chrome"
                : ua.contains("safari/") && ua.contains("version/") ? "Safari" : null;
        String os = ua.contains("android") ? "Android"
                : ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod") ? "iOS"
                : ua.contains("windows") ? "Windows"
                : ua.contains("macintosh") || ua.contains("mac os x") ? "macOS"
                : ua.contains("linux") ? "Linux" : null;
        if (browser == null && os == null) return UNKNOWN_DEVICE;
        return (browser == null ? "Navegador no identificado" : browser) + " · "
                + (os == null ? "Sistema operativo no identificado" : os);
    }

    public static final String AUTHENTICATED_AT = LoginSessionPolicy.class.getName() + ".AUTHENTICATED_AT";
    public static final String ABSOLUTE_EXPIRES_AT = LoginSessionPolicy.class.getName() + ".ABSOLUTE_EXPIRES_AT";

    /** Missing metadata identifies a legacy session: require authentication instead of inventing dates. */
    public static boolean isAbsolutelyExpired(Object authenticatedAt, Object expiresAt, Instant now) {
        return !(authenticatedAt instanceof Instant start) || !(expiresAt instanceof Instant end)
                || start.isAfter(now) || !end.isAfter(start) || !now.isBefore(end);
    }

    private static final String COOKIE_MAX_AGE = LoginSessionPolicy.class.getName() + ".COOKIE_MAX_AGE";
    private final int normalSeconds;
    private final int persistentSeconds;

    public LoginSessionPolicy(Duration normalTimeout, Duration persistentTimeout) {
        this.normalSeconds = Math.toIntExact(normalTimeout.getSeconds());
        this.persistentSeconds = Math.toIntExact(persistentTimeout.getSeconds());
        if (normalSeconds <= 0 || persistentSeconds <= normalSeconds) {
            throw new IllegalArgumentException("Persistent session timeout must exceed the positive normal timeout");
        }
        setUseHttpOnlyCookie(true);
        setSameSite("Lax");
        // Preserve DefaultCookieSerializer's Secure behaviour: request.isSecure().
    }

    /** Only called after authentication succeeds, including the verified-email check. */
    public void onAuthenticationSuccess(HttpServletRequest request) {
        boolean persistent = "true".equals(request.getParameter("rememberMe"));
        var session = request.getSession();
        session.setAttribute(DEVICE_DESCRIPTION, describeDevice(request.getHeader("User-Agent")));
        Instant authenticatedAt = Instant.now();
        session.setAttribute(AUTHENTICATED_AT, authenticatedAt);
        session.setAttribute(ABSOLUTE_EXPIRES_AT, authenticatedAt.plus(persistent ? Duration.ofDays(7) : Duration.ofHours(24)));
        session.setMaxInactiveInterval(persistent ? persistentSeconds : normalSeconds);
        if (persistent) {
            session.setAttribute(COOKIE_MAX_AGE, persistentSeconds);
        } else {
            // Explicitly downgrade an existing persistent session on a new normal login.
            session.removeAttribute(COOKIE_MAX_AGE);
        }
    }

    @Override
    public void writeCookieValue(CookieValue value) {
        // Leave deletion cookies (Max-Age=0) intact; never create a session while clearing one.
        if (!value.getCookieValue().isEmpty() && value.getCookieMaxAge() != 0) {
            var session = value.getRequest().getSession(false);
            if (session != null && session.getAttribute(COOKIE_MAX_AGE) instanceof Integer seconds) {
                // Per response, not a mutation of the shared serializer's global cookie policy.
                value.setCookieMaxAge(seconds);
            }
        }
        super.writeCookieValue(value);
    }
}
