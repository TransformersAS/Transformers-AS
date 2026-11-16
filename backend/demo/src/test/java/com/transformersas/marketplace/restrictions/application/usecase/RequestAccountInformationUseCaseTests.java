package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountInformationRequest;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectory;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectoryEntry;
import com.transformersas.marketplace.restrictions.domain.repository.AccountInformationRequestRepository;
import com.transformersas.marketplace.users.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A3/A4: el administrador pide una aclaración antes de decidir, una sola abierta a la vez. El caso de uso calcula
 * "ahora" con el reloj real, así que las fechas se anclan a {@link LocalDateTime#now()}.
 */
class RequestAccountInformationUseCaseTests {

    private static final LocalDateTime NOW = LocalDateTime.now();

    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final AccountInformationRequestRepository requests = mock(AccountInformationRequestRepository.class);
    private final RestrictionNotices notices = mock(RestrictionNotices.class);
    private final RestrictionAudit audit = mock(RestrictionAudit.class);
    private final RequestAccountInformationUseCase useCase = new RequestAccountInformationUseCase(accounts,
            requests, notices, audit);

    private void givenAnExistingAccount() {
        when(accounts.findById(7L)).thenReturn(Optional.of(new AccountDirectoryEntry(7L, "cuenta@example.com", true,
                Set.of(Role.COMPRADOR))));
    }

    @Test
    void anUnknownAccountIsRejected() {
        when(accounts.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(1L, 7L, "Explica esta situación"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("ACCOUNT_NOT_FOUND"));
        verify(requests, never()).insert(any());
    }

    @Test
    void aSecondOpenRequestIsRejectedWhileTheFirstIsStillPending() {
        givenAnExistingAccount();
        AccountInformationRequest open = new AccountInformationRequest(5L, 7L, 1L, "Primera pregunta", NOW,
                NOW.plusHours(72), AccountInformationRequest.Status.OPEN, null, null);
        when(requests.findOpenByAccount(7L)).thenReturn(Optional.of(open));

        assertThatThrownBy(() -> useCase.execute(1L, 7L, "Segunda pregunta"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("INFORMATION_ALREADY_OPEN");
                });
        verify(requests, never()).expire(any());
    }

    @Test
    void anExpiredOpenRequestIsClosedAndANewOneCanBeAsked() {
        givenAnExistingAccount();
        AccountInformationRequest expired = new AccountInformationRequest(5L, 7L, 1L, "Primera pregunta",
                NOW.minusHours(80), NOW.minusHours(8), AccountInformationRequest.Status.OPEN, null, null);
        when(requests.findOpenByAccount(7L)).thenReturn(Optional.of(expired));
        when(requests.insert(any())).thenReturn(6L);
        when(requests.findById(6L)).thenReturn(Optional.of(new AccountInformationRequest(6L, 7L, 1L,
                "Segunda pregunta", NOW, NOW.plusHours(72), AccountInformationRequest.Status.OPEN, null, null)));

        AccountInformationRequest created = useCase.execute(1L, 7L, "Segunda pregunta");

        assertThat(created.id()).isEqualTo(6L);
        verify(requests).expire(5L);
        verify(notices).informationRequested(any(), eq(6L));
        verify(audit).record(eq(1L), eq("ACCOUNT_INFORMATION_REQUESTED"), eq(7L), any());
    }
}
