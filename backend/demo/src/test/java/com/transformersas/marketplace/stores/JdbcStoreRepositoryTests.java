package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.AssignStoreOwnerUseCase;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreModificationPolicy;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adaptador JDBC de tiendas contra MySQL real: lectura, edición con versión, nombre único y dueña. */
class JdbcStoreRepositoryTests extends AbstractIntegrationTest {

    @Autowired StoreRepository stores;
    @Autowired StoreModificationPolicy policy;
    @Autowired AssignStoreOwnerUseCase assignOwner;

    private void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test
    void findsTheMigratedStoreOneAndReturnsEmptyForUnknownIds() {
        Store store = stores.findById(1L).orElseThrow();

        assertThat(store.profile().name()).isEqualTo("Tienda principal");
        assertThat(store.profile().description()).isNull();
        assertThat(store.status()).isEqualTo(StoreStatus.ACTIVE);
        assertThat(store.ownerAccountId()).isNull();
        assertThat(store.version()).isZero();
        assertThat(stores.findById(999L)).isEmpty();
    }

    @Test
    void findsTheStoreByItsOwnerAccount() {
        long owner = createAccount("owner@example.com", "VENDEDOR");
        assertThat(stores.findByOwnerAccountId(owner)).isEmpty();

        assignStoreOwner(1, owner);

        assertThat(stores.findByOwnerAccountId(owner)).get().extracting(Store::id).isEqualTo(1L);
    }

    @Test
    void nameExistenceIgnoresCaseAndAccentsAndCanExcludeTheStoreItself() {
        seedStore(2, "Café Ñandú");

        assertThat(stores.existsByName("CAFE NANDU", null)).isTrue();
        assertThat(stores.existsByName("cafe nandu", 1L)).isTrue();
        assertThat(stores.existsByName("cafe nandu", 2L)).isFalse();
        assertThat(stores.existsByName("Nombre libre", null)).isFalse();
    }

    @Test
    void savePersistsTheProfileAndAdvancesTheVersion() {
        Store loaded = stores.findById(1L).orElseThrow();

        Store saved = stores.save(loaded.withProfile(new StoreProfile("Nueva tienda", "Con descripción")));

        assertThat(saved.profile().name()).isEqualTo("Nueva tienda");
        assertThat(saved.profile().description()).isEqualTo("Con descripción");
        assertThat(saved.version()).isEqualTo(1);
        assertThat(stores.findById(1L)).contains(saved);
    }

    @Test
    void aStaleVersionIsRejectedAndLeavesTheFirstEditIntact() {
        Store loaded = stores.findById(1L).orElseThrow();
        stores.save(loaded.withProfile(new StoreProfile("Primera edición", null)));

        assertConflict(() -> stores.save(loaded.withProfile(new StoreProfile("Segunda edición", null))),
                "STORE_CONCURRENT_UPDATE");

        assertThat(stores.findById(1L).orElseThrow().profile().name()).isEqualTo("Primera edición");
    }

    @Test
    void aNameTakenByAnotherStoreIsRejectedWithoutChangingAnything() {
        seedStore(2, "Otra tienda");
        Store loaded = stores.findById(1L).orElseThrow();

        assertConflict(() -> stores.save(loaded.withProfile(new StoreProfile("OTRA TIENDA", null))),
                "STORE_NAME_TAKEN");

        Store unchanged = stores.findById(1L).orElseThrow();
        assertThat(unchanged.profile().name()).isEqualTo("Tienda principal");
        assertThat(unchanged.version()).isZero();
    }

    @Test
    void savingKeepsItsOwnNameAndUnknownStoresAreNotFound() {
        Store loaded = stores.findById(1L).orElseThrow();
        assertThat(stores.save(loaded.withProfile(new StoreProfile("tienda PRINCIPAL", "x"))).version()).isEqualTo(1);

        Store ghost = new Store(999L, null, new StoreProfile("Fantasma", null), StoreStatus.ACTIVE, null, 0);
        assertThatThrownBy(() -> stores.save(ghost)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(BusinessException.Kind.NOT_FOUND);
            assertThat(error.code()).isEqualTo("STORE_NOT_FOUND");
        });
    }

    @Test
    void savingNeverChangesTheStatusOrTheOwner() {
        long owner = createAccount("owner@example.com", "VENDEDOR");
        assignStoreOwner(1, owner);
        Store loaded = stores.findById(1L).orElseThrow();
        jdbc.update("UPDATE stores SET status = 'SUSPENDED', status_reason = 'Fraude' WHERE id = 1");

        stores.save(loaded.withProfile(new StoreProfile("Otro nombre", null)));

        Store after = stores.findById(1L).orElseThrow();
        assertThat(after.status()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(after.statusReason()).isEqualTo("Fraude");
        assertThat(after.ownerAccountId()).isEqualTo(owner);
    }

    @Test
    void assignOwnerOnlyFillsAnEmptyOwnerAndAnAccountOwnsOneStore() {
        long first = createAccount("first@example.com", "VENDEDOR");
        long second = createAccount("second@example.com", "VENDEDOR");
        seedStore(2, "Otra tienda");

        assertThat(assignOwner.execute(1L, first)).isTrue();
        assertThat(assignOwner.execute(1L, second)).isFalse();
        assertThat(assignOwner.execute(2L, first)).isFalse();
        assertThat(assignOwner.execute(999L, second)).isFalse();

        assertThat(stores.findById(1L).orElseThrow().ownerAccountId()).isEqualTo(first);
        assertThat(stores.findById(2L).orElseThrow().ownerAccountId()).isNull();
    }

    @Test
    void assignOwnerRequiresBothIds() {
        assertThatThrownBy(() -> assignOwner.execute(null, 1L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> assignOwner.execute(1L, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void thePolicyBlocksRestrictedStoresWithTheirReasonAndAllowsActiveOnes() {
        assertThat(policy.permissionFor(1L).allowed()).isTrue();

        jdbc.update("UPDATE stores SET status = 'RESTRICTED', status_reason = 'Reclamaciones pendientes' WHERE id = 1");

        var permission = policy.permissionFor(1L);
        assertThat(permission.allowed()).isFalse();
        assertThat(permission.reason()).isEqualTo("Reclamaciones pendientes");
    }
}
