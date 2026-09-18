package com.transformersas.marketplace.reservation;

import com.transformersas.marketplace.reservation.dto.ReservationResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reservations")
@CrossOrigin(origins = "*")
public class InventoryReservationController {

    private final InventoryReservationService reservationService;


    public InventoryReservationController(
            InventoryReservationService reservationService
    ) {
        this.reservationService =
                reservationService;
    }


    @PostMapping("/cart")
    public ResponseEntity<List<ReservationResponse>>
    reserveCart() {

        return ResponseEntity.ok(
                reservationService.reserveCart()
        );
    }
}