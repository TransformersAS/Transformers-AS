package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.logistics.domain.repository.ShippingMethodCatalog;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.CreateStoreUseCase;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/** CreateStoreUseCase y las operaciones de alta del adaptador contra MySQL real. */
class CreateStoreIntegrationTests extends AbstractIntegrationTest {

    @Autowired CreateStoreUseCase createStore;
    @Autowired StoreRepository stores;
    @MockitoSpyBean ShippingMethodCatalog catalog;

    private void assertBusiness(Runnable call, BusinessException.Kind kind, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(kind);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test
    void createsAnActiveStoreOwnedByTheAccountWithEveryMethodEnabled() {
        long owner = createAccount("nuevo@example.com", "VENDEDOR");

        Store store = createStore.execute(owner, "  Mi   Nueva Tienda ");

        assertThat(store.id()).isGreaterThan(1L);
        assertThat(store.profile().name()).isEqualTo("Mi Nueva Tienda");
        assertThat(store.ownerAccountId()).isEqualTo(owner);
        assertThat(store.status()).isEqualTo(StoreStatus.ACTIVE);
        assertThat(store.version()).isZero();
        assertThat(stores.findShippingMethods(store.id())).containsExactly("EXPRESS", "STANDARD");
        assertThat(stores.findByOwnerAccountId(owner)).contains(store);
    }

    @Test
    void aNameTakenIgnoringCaseAndAccentsIsRejectedAndNothingIsCreated() {
        long owner = createAccount("nuevo@example.com", "VENDEDOR");
        seedStore(2, "Café Ñandú");
        int before = count("stores");

        assertBusiness(() -> createStore.execute(owner, "CAFE NANDU"), BusinessException.Kind.CONFLICT,
                "STORE_NAME_TAKEN");

        assertThat(count("stores")).isEqualTo(before);
    }

    @Test
    void anAccountThatOwnsAStoreCannotCreateAnother() {
        long owner = createAccount("nuevo@example.com", "VENDEDOR");
        createStore.execute(owner, "Primera");
        int before = count("stores");

        assertBusiness(() -> createStore.execute(owner, "Segunda"), BusinessException.Kind.CONFLICT,
                "STORE_OWNER_ALREADY_HAS_STORE");

        assertThat(count("stores")).isEqualTo(before);
    }

    @Test
    void theAdapterMapsRacesAndUnknownOwnersWhenTheChecksWereBypassed() {
        long owner = createAccount("uno@example.com", "VENDEDOR");
        long other = createAccount("dos@example.com", "VENDEDOR");
        stores.insert(owner, new StoreProfile("Original", null));

        assertBusiness(() -> stores.insert(other, new StoreProfile("ORIGINAL", null)),
                BusinessException.Kind.CONFLICT, "STORE_NAME_TAKEN");
        assertBusiness(() -> stores.insert(owner, new StoreProfile("Distinta", null)),
                BusinessException.Kind.CONFLICT, "STORE_OWNER_ALREADY_HAS_STORE");
        assertBusiness(() -> stores.insert(999_999L, new StoreProfile("Sin dueña real", null)),
                BusinessException.Kind.INVALID, "STORE_OWNER_NOT_FOUND");
    }

    @Test
    void replacingShippingMethodsLeavesExactlyTheGivenOnes() {
        assertThat(stores.findShippingMethods(1L)).containsExactly("EXPRESS", "STANDARD");

        stores.replaceShippingMethods(1L, List.of("EXPRESS", "EXPRESS"));
        assertThat(stores.findShippingMethods(1L)).containsExactly("EXPRESS");

        stores.replaceShippingMethods(1L, List.of("STANDARD", "EXPRESS"));
        assertThat(stores.findShippingMethods(1L)).containsExactly("EXPRESS", "STANDARD");
    }

    @Test
    void aFailureAfterInsertingLeavesNoStoreBehind() {
        long owner = createAccount("falla@example.com", "VENDEDOR");
        int before = count("stores");
        doThrow(new IllegalStateException("catálogo caído")).when(catalog).availableMethods();

        assertThatThrownBy(() -> createStore.execute(owner, "Se revierte")).isInstanceOf(IllegalStateException.class);

        assertThat(count("stores")).isEqualTo(before);
        assertThat(stores.findByOwnerAccountId(owner)).isEmpty();
    }
}
