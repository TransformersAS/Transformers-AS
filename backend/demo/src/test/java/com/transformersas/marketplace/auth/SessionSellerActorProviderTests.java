package com.transformersas.marketplace.auth;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.auth.infrastructure.security.SessionSellerActorProvider;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.ResolveSellerStoreUseCase;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Identidad del vendedor: la cuenta sale de la sesión y la tienda la resuelve la relación cuenta-tienda (RF-062). */
class SessionSellerActorProviderTests {

    private static final String HASH = new BCryptPasswordEncoder(4).encode("x");
    private final ResolveSellerStoreUseCase resolveStore = mock(ResolveSellerStoreUseCase.class);
    private final SessionSellerActorProvider provider = new SessionSellerActorProvider(resolveStore);

    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void authenticate(Long accountId, Role... roles) {
        var account = new UserAccount(accountId, "seller@example.com", HASH, AccountStatus.ACTIVA, Set.of(roles));
        var principal = new AccountPrincipal(account);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private void request(String storeHeader) {
        var request = new MockHttpServletRequest();
        if (storeHeader != null) {
            request.addHeader("X-Store-Id", storeHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void assertFails(Runnable call, BusinessException.Kind kind, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(kind);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test
    void sellerSessionWithStoreHeaderResolvesBothIdentities() {
        authenticate(15L, Role.VENDEDOR);
        request("2");
        when(resolveStore.execute(15L, 2L)).thenReturn(2L);

        assertThat(provider.actorId()).isEqualTo(15L);
        assertThat(provider.storeId()).isEqualTo(2L);
        verify(resolveStore).execute(15L, 2L);
    }

    @Test
    void theOwnershipCheckFailureOfTheUseCaseIsNotSwallowed() {
        authenticate(15L, Role.VENDEDOR);
        request("2");
        when(resolveStore.execute(15L, 2L)).thenThrow(
                BusinessException.forbidden("STORE_NOT_AUTHORIZED", "ajena"));

        assertFails(provider::storeId, BusinessException.Kind.FORBIDDEN, "STORE_NOT_AUTHORIZED");
    }

    @Test
    void withoutAuthenticationIsUnauthenticated() {
        request("1");
        assertFails(provider::storeId, BusinessException.Kind.UNAUTHENTICATED, "UNAUTHENTICATED");
        assertFails(provider::actorId, BusinessException.Kind.UNAUTHENTICATED, "UNAUTHENTICATED");
    }

    @Test
    void buyerRoleIsForbidden() {
        authenticate(9L, Role.COMPRADOR);
        request("1");
        assertFails(provider::storeId, BusinessException.Kind.FORBIDDEN, "SELLER_ROLE_REQUIRED");
    }

    @Test
    void accountWithSeveralRolesWithoutActiveSellerRoleIsForbidden() {
        authenticate(9L, Role.COMPRADOR, Role.VENDEDOR); // sin rol activo elegido
        request("1");
        assertFails(provider::actorId, BusinessException.Kind.FORBIDDEN, "SELLER_ROLE_REQUIRED");
    }

    @Test
    void missingOrBlankStoreHeaderFallsBackToTheAccountsStore() {
        authenticate(15L, Role.VENDEDOR);
        when(resolveStore.execute(15L, null)).thenReturn(7L);
        for (String header : new String[]{null, "", "  "}) {
            request(header);
            assertThat(provider.storeId()).isEqualTo(7L);
        }
    }

    @Test
    void invalidStoreHeaderIsUnauthenticatedWithoutConsultingTheStore() {
        authenticate(15L, Role.VENDEDOR);
        for (String header : new String[]{"abc", "0", "-3", "1.5"}) {
            request(header);
            assertFails(provider::storeId, BusinessException.Kind.UNAUTHENTICATED, "STORE_IDENTITY_MISSING");
        }
        verifyNoInteractions(resolveStore);
    }

    @Test
    void outsideARequestTheAccountsStoreIsUsed() {
        authenticate(15L, Role.VENDEDOR);
        when(resolveStore.execute(15L, null)).thenReturn(7L);

        assertThat(provider.storeId()).isEqualTo(7L);
    }
}
