package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invariantes de la tienda: dueña, estado con motivo (A8) y edición del perfil. */
class StoreTests {

    private static final StoreProfile PROFILE = new StoreProfile("Tienda", "Descripción");

    @Test
    void activeStoreNeedsNoReasonAndBlankReasonBecomesNull() {
        assertThat(new Store(1L, 7L, PROFILE, StoreStatus.ACTIVE, "  ", 0).statusReason()).isNull();
    }

    @Test
    void restrictedOrSuspendedStoreRequiresAReason() {
        assertThatThrownBy(() -> new Store(1L, 7L, PROFILE, StoreStatus.RESTRICTED, null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RESTRICTED");
        assertThatThrownBy(() -> new Store(1L, 7L, PROFILE, StoreStatus.SUSPENDED, "   ", 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SUSPENDED");
    }

    @Test
    void reasonIsStrippedAndBoundedByTheMaximum() {
        assertThat(new Store(1L, 7L, PROFILE, StoreStatus.SUSPENDED, "  Fraude  ", 0).statusReason())
                .isEqualTo("Fraude");
        assertThat(new Store(1L, 7L, PROFILE, StoreStatus.SUSPENDED, "r".repeat(Store.REASON_MAX), 0)
                .statusReason()).hasSize(Store.REASON_MAX);
        assertThatThrownBy(() -> new Store(1L, 7L, PROFILE, StoreStatus.SUSPENDED, "r".repeat(Store.REASON_MAX + 1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownerMustBePositiveWhenPresentAndMayBeAbsentForLegacyStores() {
        assertThat(new Store(1L, null, PROFILE, StoreStatus.ACTIVE, null, 0).ownerAccountId()).isNull();
        assertThatThrownBy(() -> new Store(1L, 0L, PROFILE, StoreStatus.ACTIVE, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileAndStatusAreRequired() {
        assertThatThrownBy(() -> new Store(1L, 7L, null, StoreStatus.ACTIVE, null, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Store(1L, 7L, PROFILE, null, null, 0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void isOwnedByOnlyMatchesTheOwnerAccount() {
        Store owned = new Store(1L, 7L, PROFILE, StoreStatus.ACTIVE, null, 0);
        assertThat(owned.isOwnedBy(7L)).isTrue();
        assertThat(owned.isOwnedBy(8L)).isFalse();
        assertThat(owned.isOwnedBy(null)).isFalse();
        assertThat(new Store(1L, null, PROFILE, StoreStatus.ACTIVE, null, 0).isOwnedBy(7L)).isFalse();
    }

    @Test
    void withProfileChangesOnlyTheProfile() {
        Store suspended = new Store(1L, 7L, PROFILE, StoreStatus.SUSPENDED, "Fraude", 3);
        Store edited = suspended.withProfile(new StoreProfile("Otro nombre", null));

        assertThat(edited.profile().name()).isEqualTo("Otro nombre");
        assertThat(edited).usingRecursiveComparison().ignoringFields("profile").isEqualTo(suspended);
    }
}
