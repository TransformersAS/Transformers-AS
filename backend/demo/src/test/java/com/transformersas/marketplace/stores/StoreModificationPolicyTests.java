package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.StatusBasedModificationPolicy;
import com.transformersas.marketplace.stores.domain.model.ModificationPermission;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A8: una tienda restringida o suspendida no se modifica y el vendedor recibe el motivo. */
class StoreModificationPolicyTests {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final StatusBasedModificationPolicy policy = new StatusBasedModificationPolicy(stores);

    private void givenStore(StoreStatus status, String reason) {
        when(stores.findById(5L)).thenReturn(Optional.of(
                new Store(5L, 7L, new StoreProfile("Tienda", null), status, reason, 0)));
    }

    @Test
    void activeStoreCanBeModified() {
        givenStore(StoreStatus.ACTIVE, null);

        ModificationPermission permission = policy.permissionFor(5L);

        assertThat(permission.allowed()).isTrue();
        assertThatCode(permission::requireAllowed).doesNotThrowAnyException();
    }

    @Test
    void restrictedAndSuspendedStoresCannotBeModifiedAndCarryTheReason() {
        givenStore(StoreStatus.RESTRICTED, "Reclamaciones pendientes");
        ModificationPermission restricted = policy.permissionFor(5L);
        assertThat(restricted.allowed()).isFalse();
        assertThat(restricted.status()).isEqualTo(StoreStatus.RESTRICTED);
        assertThat(restricted.reason()).isEqualTo("Reclamaciones pendientes");

        givenStore(StoreStatus.SUSPENDED, "Incumplimiento de políticas");
        ModificationPermission suspended = policy.permissionFor(5L);
        assertThat(suspended.allowed()).isFalse();
        assertThat(suspended.status()).isEqualTo(StoreStatus.SUSPENDED);
    }

    @Test
    void requireAllowedRespondsForbiddenWithStatusAndReasonInDetails() {
        givenStore(StoreStatus.SUSPENDED, "Incumplimiento de políticas");

        assertThatThrownBy(() -> policy.permissionFor(5L).requireAllowed())
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.kind()).isEqualTo(BusinessException.Kind.FORBIDDEN);
                    assertThat(error.code()).isEqualTo("STORE_MODIFICATION_BLOCKED");
                    assertThat(error.getMessage()).contains("suspended", "Incumplimiento de políticas");
                    assertThat(error.details()).isEqualTo(
                            Map.of("status", "SUSPENDED", "reason", "Incumplimiento de políticas"));
                });
    }

    @Test
    void unknownStoreIsNotFound() {
        when(stores.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policy.permissionFor(99L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.kind()).isEqualTo(BusinessException.Kind.NOT_FOUND);
                    assertThat(error.code()).isEqualTo("STORE_NOT_FOUND");
                });
    }
}
