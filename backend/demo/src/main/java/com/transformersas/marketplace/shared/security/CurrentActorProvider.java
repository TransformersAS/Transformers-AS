package com.transformersas.marketplace.shared.security;

/**
 * Contrato transversal de identidad del vendedor en curso. Los endpoints de vendedor nunca reciben la
 * tienda por path ni body: la obtienen de aquí. Lanza BusinessException 401/403 si no hay identidad válida.
 */
public interface CurrentActorProvider {

    /** Tienda a la que pertenece el vendedor autenticado. */
    Long storeId();

    /** Cuenta del actor autenticado. */
    Long actorId();
}
