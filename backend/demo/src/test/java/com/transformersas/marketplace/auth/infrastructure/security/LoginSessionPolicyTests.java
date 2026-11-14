package com.transformersas.marketplace.auth.infrastructure.security;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class LoginSessionPolicyTests {
    private final LoginSessionPolicy policy = new LoginSessionPolicy(Duration.ofMinutes(30), Duration.ofDays(7));

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Safari/537.36|Chrome · Windows",
            "Mozilla/5.0 (X11; Linux x86_64) Firefox/130.0|Firefox · Linux",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Version/18.0 Mobile Safari/604.1|Safari · iOS",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X) Version/18.0 Safari/605.1|Safari · macOS",
            "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Safari/537.36|Chrome · Android",
            "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Safari/537.36 Edg/130.0|Edge · Windows",
            "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Safari/537.36 OPR/100.0|Opera · Windows",
            "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) CriOS/130.0 Safari/604.1|Chrome · iOS",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) FxiOS/130.0 Safari/604.1|Firefox · iOS",
            "Firefox/130.0|Firefox · Sistema operativo no identificado"
    })
    void successfulAuthenticationStoresOnlyApproximateDescription(String userAgent, String expected) {
        var request = new MockHttpServletRequest();
        request.addHeader("User-Agent", userAgent);
        policy.onAuthenticationSuccess(request);
        assertThat(request.getSession().getAttribute(LoginSessionPolicy.DEVICE_DESCRIPTION)).isEqualTo(expected);
        assertThat(request.getSession().getAttribute(LoginSessionPolicy.DEVICE_DESCRIPTION)).isNotEqualTo(userAgent);
    }

    @Test
    void missingUnknownOversizedAndMaliciousHeadersHaveSafeFallback() {
        for (String ua : new String[]{null, "", "unknown", "<script>alert('secret')</script>", "Chrome/1 Windows " + "x".repeat(10000)}) {
            var request = new MockHttpServletRequest();
            if (ua != null) request.addHeader("User-Agent", ua);
            policy.onAuthenticationSuccess(request);
            assertThat(request.getSession().getAttribute(LoginSessionPolicy.DEVICE_DESCRIPTION))
                    .isEqualTo(LoginSessionPolicy.UNKNOWN_DEVICE);
        }
        var request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "Chrome/1 Windows <script>secret</script>");
        policy.onAuthenticationSuccess(request);
        assertThat(request.getSession().getAttribute(LoginSessionPolicy.DEVICE_DESCRIPTION)).isEqualTo("Chrome · Windows");
    }
}
