package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.ResolveSellerStoreUseCase;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RF-062: la tienda con la que opera un vendedor sale de la relación cuenta-tienda. */
class ResolveSellerStoreUseCaseTests {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final ResolveSellerStoreUseCase useCase = new ResolveSellerStoreUseCase(stores);

    private static Store store(long id, Long owner) {
        return new Store(id, owner, new StoreProfile("Tienda " + id, null), StoreStatus.ACTIVE, null, 0);
    }

    private void assertFails(Runnable call, BusinessException.Kind kind, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(kind);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test
    void theRequestedStoreIsAcceptedWhenTheAccountOwnsIt() {
        when(stores.findById(2L)).thenReturn(Optional.of(store(2, 15L)));

        assertThat(useCase.execute(15L, 2L)).isEqualTo(2L);
    }

    @Test
    void aStoreOwnedByAnotherAccountIsForbidden() {
        when(stores.findById(2L)).thenReturn(Optional.of(store(2, 99L)));

        assertFails(() -> useCase.execute(15L, 2L), BusinessException.Kind.FORBIDDEN, "STORE_NOT_AUTHORIZED");
    }

    @Test
    void aStoreWithoutOwnerIsForbiddenForEveryone() {
        when(stores.findById(1L)).thenReturn(Optional.of(store(1, null)));

        assertFails(() -> useCase.execute(15L, 1L), BusinessException.Kind.FORBIDDEN, "STORE_NOT_AUTHORIZED");
    }

    @Test
    void anUnknownStoreGetsTheSameResponseAsAForeignOne() {
        when(stores.findById(999L)).thenReturn(Optional.empty());
        when(stores.findById(2L)).thenReturn(Optional.of(store(2, 99L)));

        BusinessException unknown = catchBusiness(() -> useCase.execute(15L, 999L));
        BusinessException foreign = catchBusiness(() -> useCase.execute(15L, 2L));

        assertThat(unknown.kind()).isEqualTo(foreign.kind());
        assertThat(unknown.code()).isEqualTo(foreign.code());
        assertThat(unknown.getMessage()).isEqualTo(foreign.getMessage());
    }

    @Test
    void withoutARequestedStoreTheAccountsOwnStoreIsUsed() {
        when(stores.findByOwnerAccountId(15L)).thenReturn(Optional.of(store(2, 15L)));

        assertThat(useCase.execute(15L, null)).isEqualTo(2L);
    }

    @Test
    void withoutARequestedStoreAnAccountWithoutStoreHasNoIdentity() {
        when(stores.findByOwnerAccountId(15L)).thenReturn(Optional.empty());

        assertFails(() -> useCase.execute(15L, null), BusinessException.Kind.UNAUTHENTICATED, "STORE_IDENTITY_MISSING");
    }

    private BusinessException catchBusiness(Runnable call) {
        try {
            call.run();
        } catch (BusinessException error) {
            return error;
        }
        throw new AssertionError("Se esperaba BusinessException");
    }
}
