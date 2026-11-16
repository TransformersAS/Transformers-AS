package com.transformersas.marketplace.restrictions.domain.port;

import java.util.Optional;

/**
 * Cuentas del marketplace, para que el administrador las busque y las evalúe (pasos 2 a 4). Restricciones no
 * conoce la persistencia de cuentas: solo este puerto, que implementa un adaptador de {@code users}.
 */
public interface AccountDirectory {

    /** Coincidencia exacta de correo (paso 2); vacío si no existe ninguna cuenta con ese correo. */
    Optional<AccountDirectoryEntry> findByEmail(String email);

    Optional<AccountDirectoryEntry> findById(Long accountId);
}
