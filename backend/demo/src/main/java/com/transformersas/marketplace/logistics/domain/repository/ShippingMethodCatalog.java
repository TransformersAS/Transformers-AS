package com.transformersas.marketplace.logistics.domain.repository;

import java.util.List;

/**
 * Métodos de envío que ofrece el marketplace a través de la integración logística (RF-061). Una tienda solo puede
 * habilitar métodos de este catálogo.
 */
public interface ShippingMethodCatalog {

    /** Nombres de los métodos disponibles, sin repetidos y en un orden estable. Nunca vacío. */
    List<String> availableMethods();
}
