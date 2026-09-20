package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.stores.application.usecase.AssignMainStoreOwnerUseCase;
import com.transformersas.marketplace.stores.application.usecase.AssignMainStoreOwnerUseCase.Outcome;
import com.transformersas.marketplace.stores.application.usecase.AssignStoreOwnerUseCase;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import com.transformersas.marketplace.users.application.usecase.FindAccountByEmail;
import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/** MAIN_STORE_OWNER_EMAIL: solo llena una dueña vacía, con una cuenta VENDEDOR, y cuenta lo que ocurrió. */
class AssignMainStoreOwnerUseCaseTests {

    private static final String HASH = "$2a$04$" + "a".repeat(53);

    private final StoreRepository stores = mock(StoreRepository.class);
    private final FindAccountByEmail accounts = mock(FindAccountByEmail.class);
    private final AssignStoreOwnerUseCase assignOwner = mock(AssignStoreOwnerUseCase.class);
    private final AssignMainStoreOwnerUseCase useCase = new AssignMainStoreOwnerUseCase(stores, accounts, assignOwner);

    private static Store store(Long owner) {
        return new Store(1L, owner, new StoreProfile("Tienda principal", null), StoreStatus.ACTIVE, null, 0);
    }

    private static UserAccount account(long id, Role... roles) {
        return new UserAccount(id, "vendedor@example.com", HASH, AccountStatus.ACTIVA, Set.of(roles));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void withoutAnEmailNothingIsReadOrWritten(String email) {
        assertThat(useCase.execute(email)).isEqualTo(Outcome.NOT_CONFIGURED);

        verifyNoInteractions(stores, accounts, assignOwner);
    }

    @Test
    void anUnownedStoreIsAssignedToASellerAccount() {
        when(stores.findById(1L)).thenReturn(Optional.of(store(null)));
        when(accounts.execute("vendedor@example.com")).thenReturn(Optional.of(account(7, Role.VENDEDOR)));
        when(assignOwner.execute(1L, 7L)).thenReturn(true);

        assertThat(useCase.execute("vendedor@example.com")).isEqualTo(Outcome.ASSIGNED);
    }

    @Test
    void aMultiRoleAccountWithTheSellerRoleIsAccepted() {
        when(stores.findById(1L)).thenReturn(Optional.of(store(null)));
        when(accounts.execute("vendedor@example.com")).thenReturn(Optional.of(account(7, Role.COMPRADOR, Role.VENDEDOR)));
        when(assignOwner.execute(1L, 7L)).thenReturn(true);

        assertThat(useCase.execute("vendedor@example.com")).isEqualTo(Outcome.ASSIGNED);
    }

    @Test
    void anExistingOwnerIsNeverReplacedNorEvenLookedUp() {
        when(stores.findById(1L)).thenReturn(Optional.of(store(3L)));

        assertThat(useCase.execute("vendedor@example.com")).isEqualTo(Outcome.ALREADY_HAS_OWNER);

        verifyNoInteractions(accounts, assignOwner);
    }

    @Test
    void aMissingStoreIsReported() {
        when(stores.findById(1L)).thenReturn(Optional.empty());

        assertThat(useCase.execute("vendedor@example.com")).isEqualTo(Outcome.STORE_NOT_FOUND);
        verifyNoInteractions(assignOwner);
    }

    @Test
    void anUnknownAccountIsReportedWithoutAssigning() {
        when(stores.findById(1L)).thenReturn(Optional.of(store(null)));
        when(accounts.execute("nadie@example.com")).thenReturn(Optional.empty());

        assertThat(useCase.execute("nadie@example.com")).isEqualTo(Outcome.ACCOUNT_NOT_FOUND);
        verifyNoInteractions(assignOwner);
    }

    @Test
    void anAccountWithoutTheSellerRoleIsRejected() {
        when(stores.findById(1L)).thenReturn(Optional.of(store(null)));
        when(accounts.execute("comprador@example.com")).thenReturn(Optional.of(account(8, Role.COMPRADOR)));

        assertThat(useCase.execute("comprador@example.com")).isEqualTo(Outcome.ACCOUNT_NOT_SELLER);
        verify(assignOwner, never()).execute(any(), any());
    }

    @Test
    void anAccountThatOwnsAnotherStoreIsReported() {
        when(stores.findById(1L)).thenReturn(Optional.of(store(null)));
        when(accounts.execute("vendedor@example.com")).thenReturn(Optional.of(account(7, Role.VENDEDOR)));
        when(assignOwner.execute(1L, 7L)).thenReturn(false);

        assertThat(useCase.execute("vendedor@example.com")).isEqualTo(Outcome.ACCOUNT_OWNS_ANOTHER_STORE);
    }

    @Test
    void ifAnotherReplicaWinsTheRaceTheStoreIsReportedAsAlreadyOwned() {
        when(stores.findById(1L)).thenReturn(Optional.of(store(null)), Optional.of(store(9L)));
        when(accounts.execute("vendedor@example.com")).thenReturn(Optional.of(account(7, Role.VENDEDOR)));
        when(assignOwner.execute(1L, 7L)).thenReturn(false);

        assertThat(useCase.execute("vendedor@example.com")).isEqualTo(Outcome.ALREADY_HAS_OWNER);
    }

    @Test
    void ifTheStoreDisappearsAfterAFailedAssignmentItIsTreatedAsNotOwnedByThatRace() {
        when(stores.findById(1L)).thenReturn(Optional.of(store(null)), Optional.empty());
        when(accounts.execute("vendedor@example.com")).thenReturn(Optional.of(account(7, Role.VENDEDOR)));
        when(assignOwner.execute(1L, 7L)).thenReturn(false);

        assertThat(useCase.execute("vendedor@example.com")).isEqualTo(Outcome.ACCOUNT_OWNS_ANOTHER_STORE);
    }
}
