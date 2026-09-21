package com.transformersas.marketplace.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface InventoryReservationRepository
        extends JpaRepository<InventoryReservation, Long> {

    List<InventoryReservation>
    findByProduct_IdAndStatusAndExpiresAtAfter(
            Long productId,
            ReservationStatus status,
            LocalDateTime now
    );

    List<InventoryReservation>
    findByStatusAndExpiresAtBefore(
            ReservationStatus status,
            LocalDateTime now
    );

    /** CU-15: total de unidades de un producto apartadas por reservas activas que aún no vencen. */
    @Query("select coalesce(sum(r.quantity), 0) from InventoryReservation r "
            + "where r.product.id = :productId and r.status = :status and r.expiresAt > :now")
    Long sumReservedQuantity(@Param("productId") Long productId, @Param("status") ReservationStatus status,
                             @Param("now") LocalDateTime now);
}