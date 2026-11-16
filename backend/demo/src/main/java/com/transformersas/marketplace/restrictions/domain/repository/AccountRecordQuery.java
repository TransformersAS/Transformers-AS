package com.transformersas.marketplace.restrictions.domain.repository;

import com.transformersas.marketplace.restrictions.domain.model.AccountRecord;

import java.util.List;

/**
 * Modelo de lectura de antecedentes de una cuenta: reclamaciones y decisiones de moderación confirmadas contra la
 * tienda de la cuenta, cuando tiene una (RF-168, vía la misma lectura de CU-27). Las restricciones anteriores de la
 * cuenta las aporta {@link AccountRestrictionRepository} y se combinan aparte.
 */
public interface AccountRecordQuery {

    /** Reclamaciones y moderaciones confirmadas de la tienda de esta cuenta; vacío si no tiene tienda. */
    List<AccountRecord> confirmedStoreRecords(Long accountId);
}
