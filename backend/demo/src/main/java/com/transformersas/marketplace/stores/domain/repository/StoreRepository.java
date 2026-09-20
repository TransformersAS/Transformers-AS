/** Puertos de persistencia requeridos por tiendas. */
package com.transformersas.marketplace.stores.domain.repository;

import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreImage;
import com.transformersas.marketplace.stores.domain.model.StoreImageKind;
import com.transformersas.marketplace.stores.domain.model.StoreImageSummary;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreRepository {

    Optional<Store> findById(Long id);

    /** La tienda de la cuenta dueña; vacío si la cuenta no es dueña de ninguna. */
    Optional<Store> findByOwnerAccountId(Long accountId);

    /** Indica si otra tienda ya usa ese nombre (sin distinguir mayúsculas ni tildes). excludingStoreId puede ser nulo. */
    boolean existsByName(String name, Long excludingStoreId);

    /**
     * Guarda el perfil y la política de una tienda existente; no toca la dueña ni el estado. Si otra tienda ganó el mismo nombre
     * lanza BusinessException CONFLICT STORE_NAME_TAKEN, si la versión ya no es la guardada, CONFLICT
     * STORE_CONCURRENT_UPDATE, y si la tienda no existe, NOT_FOUND STORE_NOT_FOUND. Devuelve la tienda con su nueva
     * versión.
     */
    Store save(Store store);

    /**
     * Crea una tienda activa con su dueña. Lanza BusinessException CONFLICT STORE_NAME_TAKEN si el nombre ya existe,
     * CONFLICT STORE_OWNER_ALREADY_HAS_STORE si la cuenta ya es dueña de otra tienda e INVALID STORE_OWNER_NOT_FOUND
     * si la cuenta no existe.
     */
    Store insert(Long ownerAccountId, StoreProfile profile);

    /** Métodos de envío habilitados de la tienda, en orden alfabético. */
    List<String> findShippingMethods(Long storeId);

    /** Reemplaza los métodos de envío habilitados por exactamente los indicados. */
    void replaceShippingMethods(Long storeId, Collection<String> methods);

    /** Reemplaza la imagen de ese tipo o la crea. Una sola sentencia: nunca queda la tienda sin imagen a medias. */
    void saveImage(Long storeId, StoreImage image);

    /** La imagen con su contenido, para servirla. */
    Optional<StoreImage> findImage(Long storeId, StoreImageKind kind);

    /** Las imágenes de la tienda sin su contenido, ordenadas por tipo. */
    List<StoreImageSummary> findImageSummaries(Long storeId);

    /** Elimina la imagen de ese tipo. Devuelve false si no existía. */
    boolean deleteImage(Long storeId, StoreImageKind kind);

    /**
     * Hace de la cuenta la dueña de una tienda que aún no tiene. Devuelve false, sin cambios, si la tienda no existe,
     * ya tiene dueña o la cuenta ya es dueña de otra tienda.
     */
    boolean assignOwner(Long storeId, Long accountId);
}
