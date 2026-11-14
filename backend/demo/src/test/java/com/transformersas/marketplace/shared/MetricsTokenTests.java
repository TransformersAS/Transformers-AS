package com.transformersas.marketplace.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsTokenTests {

    @Test
    void acceptsOnlyTheConfiguredBearerToken() {
        assertThat(MetricsToken.matches("secreto", "Bearer secreto")).isTrue();
        assertThat(MetricsToken.matches(" secreto ", "Bearer secreto ")).isTrue();
        assertThat(MetricsToken.matches("secreto", "Bearer otro")).isFalse();
        assertThat(MetricsToken.matches("secreto", "secreto")).isFalse();
        assertThat(MetricsToken.matches("secreto", "Basic secreto")).isFalse();
        assertThat(MetricsToken.matches("secreto", null)).isFalse();
    }

    @Test
    void staysClosedWithoutAConfiguredToken() {
        assertThat(MetricsToken.matches(null, "Bearer algo")).isFalse();
        assertThat(MetricsToken.matches("", "Bearer ")).isFalse();
        assertThat(MetricsToken.matches("   ", "Bearer    ")).isFalse();
    }
}
