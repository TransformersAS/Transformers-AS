package com.transformersas.marketplace.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

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
}