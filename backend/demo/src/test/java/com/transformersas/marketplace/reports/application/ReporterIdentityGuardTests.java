package com.transformersas.marketplace.reports.application;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReporterIdentityGuardTests {

    private static final List<String> REPORTERS = List.of("user_123", "Maria.Perez");

    @Test
    void rejectsTextThatNamesAReporterIgnoringCase() {
        assertThatThrownBy(() -> ReporterIdentityGuard.ensureAbsent("Según USER_123 el producto es falso", REPORTERS))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> ReporterIdentityGuard.ensureAbsent("Lo reportó maria.perez", REPORTERS))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptsTextWithoutReporterData() {
        assertThatCode(() -> ReporterIdentityGuard.ensureAbsent("La publicación viola la norma de spam.", REPORTERS))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresIdsTooShortToBeMeaningful() {
        assertThatCode(() -> ReporterIdentityGuard.ensureAbsent("cualquier texto con a y b", List.of("a", "b")))
                .doesNotThrowAnyException();
    }
}
