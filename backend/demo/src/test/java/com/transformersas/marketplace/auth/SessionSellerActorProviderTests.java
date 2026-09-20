package com.transformersas.marketplace.auth;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.auth.infrastructure.security.SessionSellerActorProvider;
import com.transformersas.marketplace.shared.error.BusinessException;
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

/** D2: identidad del vendedor. La cuenta sale de la sesión; la tienda es provisional (cabecera X-Store-Id). */
class SessionSellerActorProviderTests {

    private static final String HASH = new BCryptPasswordEncoder(4).encode("x");
    private final SessionSellerActorProvider provider = new SessionSellerActorProvider();

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

        assertThat(provider.actorId()).isEqualTo(15L);
        assertThat(provider.storeId()).isEqualTo(2L);
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
    void missingOrInvalidStoreHeaderIsUnauthenticated() {
        authenticate(15L, Role.VENDEDOR);
        for (String header : new String[]{null, "", "  ", "abc", "0", "-3", "1.5"}) {
            request(header);
            assertFails(provider::storeId, BusinessException.Kind.UNAUTHENTICATED, "STORE_IDENTITY_MISSING");
        }
    }

    @Test
    void outsideARequestThereIsNoStoreIdentity() {
        authenticate(15L, Role.VENDEDOR);
        assertFails(provider::storeId, BusinessException.Kind.UNAUTHENTICATED, "STORE_IDENTITY_MISSING");
    }
}
