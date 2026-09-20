package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.logistics.domain.repository.ShippingMethodCatalog;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.CreateStoreUseCase;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Creación de la tienda: nombre y dueña, con todos los métodos de envío disponibles habilitados. */
class CreateStoreUseCaseTests {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final ShippingMethodCatalog catalog = mock(ShippingMethodCatalog.class);
    private final CreateStoreUseCase useCase = new CreateStoreUseCase(stores, catalog);

    private static Store created(long id, long owner, String name) {
        return new Store(id, owner, new StoreProfile(name, null), StoreStatus.ACTIVE, null, 0);
    }

    private void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test
    void createsTheStoreAndEnablesEveryAvailableShippingMethod() {
        when(stores.existsByName("Mi Tienda", null)).thenReturn(false);
        when(stores.findByOwnerAccountId(7L)).thenReturn(Optional.empty());
        when(stores.insert(7L, new StoreProfile("Mi Tienda", null))).thenReturn(created(5, 7, "Mi Tienda"));
        when(catalog.availableMethods()).thenReturn(List.of("STANDARD", "EXPRESS"));

        Store store = useCase.execute(7L, "  Mi   Tienda ");

        assertThat(store.id()).isEqualTo(5L);
        verify(stores).replaceShippingMethods(5L, List.of("STANDARD", "EXPRESS"));
    }

    @Test
    void aTakenNameIsRejectedBeforeInserting() {
        when(stores.existsByName("Mi Tienda", null)).thenReturn(true);

        assertConflict(() -> useCase.execute(7L, "Mi Tienda"), "STORE_NAME_TAKEN");
        verify(stores, never()).insert(any(), any());
    }

    @Test
    void anAccountThatAlreadyOwnsAStoreIsRejectedBeforeInserting() {
        when(stores.findByOwnerAccountId(7L)).thenReturn(Optional.of(created(2, 7, "Ya tengo")));

        assertConflict(() -> useCase.execute(7L, "Otra"), "STORE_OWNER_ALREADY_HAS_STORE");
        verify(stores, never()).insert(any(), any());
    }

    @Test
    void anInvalidNameAndAMissingOwnerAreRejected() {
        assertThatThrownBy(() -> useCase.execute(7L, "   ")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.code()).isEqualTo("STORE_NAME_REQUIRED"));
        assertThatThrownBy(() -> useCase.execute(null, "Mi Tienda")).isInstanceOf(NullPointerException.class);
        verify(stores, never()).insert(any(), any());
    }
}
