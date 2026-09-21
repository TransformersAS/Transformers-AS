package com.transformersas.marketplace.returns.domain.port;

import java.util.Optional;

/** Plazo de devolución que la tienda configuró (CU-18, stores.return_window_days). */
public interface StoreReturnPolicyReader {

    /** Días de plazo; vacío si la tienda no existe. */
    Optional<Integer> returnWindowDays(Long storeId);
}
