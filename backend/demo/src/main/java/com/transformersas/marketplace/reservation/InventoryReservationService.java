package com.transformersas.marketplace.reservation;

import com.transformersas.marketplace.cart.Cart;
import com.transformersas.marketplace.cart.CartItem;
import com.transformersas.marketplace.cart.CartItemRepository;
import com.transformersas.marketplace.cart.CartRepository;
import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.reservation.dto.ReservationResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class InventoryReservationService {

    private static final long RESERVATION_MINUTES = 10;

    private final InventoryReservationRepository reservationRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;


    public InventoryReservationService(
            InventoryReservationRepository reservationRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository
    ) {
        this.reservationRepository = reservationRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
    }


    @Transactional
    public List<ReservationResponse> reserveCart() {

        expireOldReservations();

        Cart cart = cartRepository
                .findAll()
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "No existe un carrito"
                        )
                );


        List<CartItem> items =
                cartItemRepository.findByCartId(
                        cart.getId()
                );


        if (items.isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El carrito está vacío"
            );
        }


        /*
         * PRIMERA PASADA:
         * validamos todo antes de guardar
         * cualquier reserva.
         */
        for (CartItem item : items) {

            Product product =
                    item.getProduct();


            if (product == null) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Uno de los productos ya no existe"
                );
            }


            if (!Boolean.TRUE.equals(
                    product.getActive()
            )) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "El producto "
                                + product.getName()
                                + " ya no está disponible"
                );
            }


            int reserved =
                    getActiveReservedQuantity(
                            product.getId()
                    );


            int available =
                    product.getStock()
                            - reserved;


            if (available < item.getQuantity()) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Stock insuficiente para "
                                + product.getName()
                                + ". Disponible: "
                                + available
                );
            }
        }


        /*
         * SEGUNDA PASADA:
         * ahora sí creamos reservas.
         */
        List<ReservationResponse> responses =
                new ArrayList<>();


        LocalDateTime now =
                LocalDateTime.now();

        LocalDateTime expiresAt =
                now.plusMinutes(
                        RESERVATION_MINUTES
                );


        for (CartItem item : items) {

            InventoryReservation reservation =
                    new InventoryReservation();

            reservation.setProduct(
                    item.getProduct()
            );

            reservation.setQuantity(
                    item.getQuantity()
            );

            reservation.setStatus(
                    ReservationStatus.ACTIVE
            );

            reservation.setCreatedAt(
                    now
            );

            reservation.setExpiresAt(
                    expiresAt
            );


            InventoryReservation saved =
                    reservationRepository.save(
                            reservation
                    );


            responses.add(
                    toResponse(saved)
            );
        }


        return responses;
    }


    /*
     * Cantidad actualmente apartada
     * para un producto.
     */
    private int getActiveReservedQuantity(
            Long productId
    ) {

        LocalDateTime now =
                LocalDateTime.now();


        return reservationRepository
                .findByProduct_IdAndStatusAndExpiresAtAfter(
                        productId,
                        ReservationStatus.ACTIVE,
                        now
                )
                .stream()
                .mapToInt(
                        InventoryReservation::getQuantity
                )
                .sum();
    }


    /*
     * Marca como EXPIRED las reservas
     * que ya superaron expires_at.
     */
    @Transactional
    public void expireOldReservations() {

        LocalDateTime now =
                LocalDateTime.now();


        List<InventoryReservation> expired =
                reservationRepository
                        .findByStatusAndExpiresAtBefore(
                                ReservationStatus.ACTIVE,
                                now
                        );


        for (
                InventoryReservation reservation
                : expired
        ) {

            reservation.setStatus(
                    ReservationStatus.EXPIRED
            );
        }


        reservationRepository.saveAll(
                expired
        );
    }


    private ReservationResponse toResponse(
            InventoryReservation reservation
    ) {

        return new ReservationResponse(
                reservation.getId(),
                reservation.getProduct().getId(),
                reservation.getProduct().getName(),
                reservation.getQuantity(),
                reservation.getStatus().name(),
                reservation.getExpiresAt()
        );
    }
}