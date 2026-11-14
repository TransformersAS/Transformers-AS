package com.transformersas.marketplace.suspensions.domain.repository;

import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionChange;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface StoreSuspensionRepository {

    /**
     * Guarda una suspensión nueva y devuelve su id. Si la tienda ya tiene una vigente lanza BusinessException CONFLICT
     * STORE_ALREADY_SUSPENDED (la base de datos admite una sola).
     */
    Long insert(StoreSuspension suspension);

    Optional<StoreSuspension> findById(Long id);

    Optional<StoreSuspension> findActiveByStore(Long storeId);

    /** Todas las suspensiones de la tienda, de la más reciente a la más antigua. */
    List<StoreSuspension> findByStore(Long storeId);

    /** Vigentes cuyo fin ya llegó, hasta {@code limit}, las más vencidas primero. */
    List<StoreSuspension> findDue(LocalDateTime now, int limit);

    /** Cambia el fin si sigue vigente y en esa versión. Devuelve false si otro proceso la cambió antes. */
    boolean changeEnd(Long id, long version, LocalDateTime newEndsAt);

    /** Cierra si sigue vigente y en esa versión. Devuelve false si otro proceso la cambió o cerró antes. */
    boolean close(Long id, long version, SuspensionStatus status, LocalDateTime closedAt, Long closedBy,
                  String closeReason);

    /** Registra un hecho de la historia y devuelve su id. */
    Long addChange(SuspensionChange change);

    /** Historia de cada suspensión indicada, en orden cronológico. */
    Map<Long, List<SuspensionChange>> findChanges(List<Long> suspensionIds);
}
