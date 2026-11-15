package com.transformersas.marketplace.restrictions.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La solicitud de información del administrador a la cuenta (A3, A4): plazo de 72 horas y su respuesta. */
class AccountInformationRequestTests {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 10, 0);

    @Test
    void askOpensARequestDueInSeventyTwoHours() {
        AccountInformationRequest request = AccountInformationRequest.ask(7L, 1L, "Explica esta situación", T0);

        assertThat(request.status()).isEqualTo(AccountInformationRequest.Status.OPEN);
        assertThat(request.dueAt()).isEqualTo(T0.plusHours(72));
    }

    @Test
    void theMessageIsRequired() {
        assertThatThrownBy(() -> AccountInformationRequest.ask(7L, 1L, "corto", T0))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BusinessException.Kind.INVALID);
                    assertThat(e.code()).isEqualTo("INFORMATION_MESSAGE_REQUIRED");
                    assertThat(e.details()).isEqualTo(Map.of("field", "message"));
                });
    }

    @Test
    void effectiveStatusBecomesExpiredOncePastTheDeadlineEvenIfStillOpen() {
        AccountInformationRequest request = AccountInformationRequest.ask(7L, 1L, "Explica esta situación", T0);

        assertThat(request.effectiveStatus(T0.plusHours(71))).isEqualTo(AccountInformationRequest.Status.OPEN);
        assertThat(request.effectiveStatus(T0.plusHours(73))).isEqualTo(AccountInformationRequest.Status.EXPIRED);
    }

    @Test
    void acceptResponseRejectsAnAlreadyAnsweredRequest() {
        AccountInformationRequest answered = new AccountInformationRequest(5L, 7L, 1L, "Explica esta situación", T0,
                T0.plusHours(72), AccountInformationRequest.Status.ANSWERED, "Ya respondí", T0.plusHours(1));

        assertThatThrownBy(() -> answered.acceptResponse("Otra respuesta", T0.plusHours(2)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("INFORMATION_ALREADY_ANSWERED");
                });
    }

    @Test
    void acceptResponseRejectsAnExpiredRequest() {
        AccountInformationRequest request = AccountInformationRequest.ask(7L, 1L, "Explica esta situación", T0);

        assertThatThrownBy(() -> request.acceptResponse("Llegó tarde", T0.plusHours(80)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("INFORMATION_EXPIRED");
                });
    }

    @Test
    void acceptResponseNormalizesAndRequiresText() {
        AccountInformationRequest request = AccountInformationRequest.ask(7L, 1L, "Explica esta situación", T0);

        assertThat(request.acceptResponse("  La respuesta  ", T0.plusHours(1))).isEqualTo("La respuesta");
        assertThatThrownBy(() -> request.acceptResponse("", T0.plusHours(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("INFORMATION_RESPONSE_REQUIRED"));
    }
}
