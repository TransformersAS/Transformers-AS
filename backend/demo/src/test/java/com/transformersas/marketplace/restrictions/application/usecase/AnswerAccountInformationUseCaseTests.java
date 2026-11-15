package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountInformationRequest;
import com.transformersas.marketplace.restrictions.domain.repository.AccountInformationRequestRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A4: la persona responde una solicitud sobre su propia cuenta dentro del plazo. */
class AnswerAccountInformationUseCaseTests {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 10, 0);

    private final AccountInformationRequestRepository requests = mock(AccountInformationRequestRepository.class);
    private final RestrictionAudit audit = mock(RestrictionAudit.class);
    private final AnswerAccountInformationUseCase useCase = new AnswerAccountInformationUseCase(requests, audit);

    private static AccountInformationRequest open() {
        return new AccountInformationRequest(5L, 7L, 1L, "Explica esta situación", LocalDateTime.now(),
                LocalDateTime.now().plusHours(72), AccountInformationRequest.Status.OPEN, null, null);
    }

    @Test
    void anotherAccountsRequestRespondsAsNotFound() {
        when(requests.findById(5L)).thenReturn(Optional.of(open()));

        assertThatThrownBy(() -> useCase.execute(999L, 5L, "No es mía"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("INFORMATION_NOT_FOUND"));
    }

    @Test
    void aConcurrentAnswerIsReportedAsAConflict() {
        when(requests.findById(5L)).thenReturn(Optional.of(open()));
        when(requests.answer(eq(5L), eq("Mi respuesta"), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(7L, 5L, "Mi respuesta"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("INFORMATION_ALREADY_ANSWERED");
                });
    }
}
