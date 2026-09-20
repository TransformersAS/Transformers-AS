package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import com.transformersas.marketplace.users.application.usecase.FindAccountByEmail;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asigna la dueña de la tienda principal (la tienda 1, anterior a CU-18) a la cuenta cuyo correo se configura. Solo
 * llena una dueña vacía: nunca reemplaza a una existente ni toca otra fila, y es seguro si varias réplicas lo
 * ejecutan a la vez porque la asignación es un UPDATE condicionado a que siga sin dueña. Devuelve qué ocurrió en
 * lugar de lanzar, para que quien lo invoque avise sin impedir el arranque.
 */
@Component
public class AssignMainStoreOwnerUseCase {

    public static final long MAIN_STORE_ID = 1L;

    public enum Outcome {
        /** No hay correo configurado: no se hace nada. */
        NOT_CONFIGURED,
        ASSIGNED,
        /** La tienda ya tiene dueña, la de antes o la que ganó una carrera. No se cambia. */
        ALREADY_HAS_OWNER,
        STORE_NOT_FOUND,
        ACCOUNT_NOT_FOUND,
        /** La cuenta existe pero no tiene el rol VENDEDOR. */
        ACCOUNT_NOT_SELLER,
        /** La cuenta ya es dueña de otra tienda, y una cuenta solo puede serlo de una. */
        ACCOUNT_OWNS_ANOTHER_STORE
    }

    private final StoreRepository stores;
    private final FindAccountByEmail accounts;
    private final AssignStoreOwnerUseCase assignOwner;

    public AssignMainStoreOwnerUseCase(StoreRepository stores, FindAccountByEmail accounts,
                                       AssignStoreOwnerUseCase assignOwner) {
        this.stores = stores;
        this.accounts = accounts;
        this.assignOwner = assignOwner;
    }

    @Transactional
    public Outcome execute(String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            return Outcome.NOT_CONFIGURED;
        }
        Store store = stores.findById(MAIN_STORE_ID).orElse(null);
        if (store == null) {
            return Outcome.STORE_NOT_FOUND;
        }
        if (store.ownerAccountId() != null) {
            return Outcome.ALREADY_HAS_OWNER;
        }
        UserAccount account = accounts.execute(ownerEmail).orElse(null);
        if (account == null) {
            return Outcome.ACCOUNT_NOT_FOUND;
        }
        if (!account.roles().contains(Role.VENDEDOR)) {
            return Outcome.ACCOUNT_NOT_SELLER;
        }
        if (assignOwner.execute(MAIN_STORE_ID, account.id())) {
            return Outcome.ASSIGNED;
        }
        boolean someoneWonTheRace = stores.findById(MAIN_STORE_ID)
                .map(current -> current.ownerAccountId() != null).orElse(false);
        return someoneWonTheRace ? Outcome.ALREADY_HAS_OWNER : Outcome.ACCOUNT_OWNS_ANOTHER_STORE;
    }
}
