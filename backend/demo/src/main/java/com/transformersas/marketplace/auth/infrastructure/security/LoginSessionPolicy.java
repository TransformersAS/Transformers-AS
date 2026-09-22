package com.transformersas.marketplace.auth.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Extends the existing JDBC session, without issuing a second authentication credential. */
public final class LoginSessionPolicy extends DefaultCookieSerializer {
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
