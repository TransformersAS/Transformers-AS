package com.transformersas.marketplace.reservation;

import com.transformersas.marketplace.auth.infrastructure.security.BuyerAccess;
import com.transformersas.marketplace.cart.Cart;
import com.transformersas.marketplace.cart.CartItem;
import com.transformersas.marketplace.cart.CartItemRepository;
import com.transformersas.marketplace.cart.CartRepository;
import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductRepository;
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

    // Duración de una reserva temporal
    private static final long RESERVATION_MINUTES = 10;

    private final InventoryReservationRepository reservationRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;


    // =========================
    // CONSTRUCTOR
    // =========================

    public InventoryReservationService(
            InventoryReservationRepository reservationRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            ProductRepository productRepository
    ) {

        this.reservationRepository = reservationRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
    }


    // =========================================================
    // CREAR RESERVAS PARA LOS PRODUCTOS DEL CARRITO
    // =========================================================

    @Transactional
    public List<ReservationResponse> reserveCart(Long accountId) {
        BuyerAccess.requireAccountId(accountId);

        /*
         * Antes de calcular disponibilidad,
         * marcamos como expiradas las reservas viejas.
         */
        expireOldReservations();


        // =========================
        // 1. OBTENER CARRITO
        // =========================

        Cart cart = cartRepository
                .findByAccountId(accountId)
                .orElseThrow(
                        () -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "No existe un carrito"
                        )
                );


        // =========================
        // 2. OBTENER ITEMS
        // =========================

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


        // =====================================================
        // 3. PRIMERA PASADA:
        // VALIDAR TODO ANTES DE CREAR RESERVAS
        // =====================================================

        for (CartItem item : items) {

            Product product =
                    item.getProduct();


            // Producto inexistente
            if (product == null) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Uno de los productos del carrito ya no existe"
                );
            }


            // Producto inactivo
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


            // Cantidad inválida
            if (item.getQuantity() == null
                    || item.getQuantity() <= 0) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Cantidad inválida para "
                                + product.getName()
                );
            }


            // Stock inválido
            if (product.getStock() == null
                    || product.getStock() < 0) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "El producto "
                                + product.getName()
                                + " no tiene stock válido"
                );
            }


            /*
             * Calculamos cuántas unidades del producto
             * ya están reservadas por compras en proceso.
             */
            int reserved =
                    getActiveReservedQuantity(
                            product.getId()
                    );


            /*
             * Ejemplo:
             *
             * stock físico = 10
             * reservado = 3
             *
             * disponible = 7
             */
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


        // =====================================================
        // 4. SEGUNDA PASADA:
        // CREAR LAS RESERVAS
        // =====================================================

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


            reservation.setAccountId(accountId);
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


    // =========================================================
    // OBTENER CANTIDAD ACTUALMENTE RESERVADA DE UN PRODUCTO
    // =========================================================

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


    // =========================================================
    // CONFIRMAR RESERVAS DESPUÉS DE PAGO APROBADO
    // =========================================================

    @Transactional
    public void confirmReservations(
            Long accountId, List<Long> reservationIds
    ) {
        BuyerAccess.requireAccountId(accountId);

        /*
         * Primero limpiamos reservas vencidas.
         */
        expireOldReservations();


        if (reservationIds == null
                || reservationIds.isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debe indicar las reservas a confirmar"
            );
        }


        List<InventoryReservation> reservations =
                ownedReservations(accountId, reservationIds);


        /*
         * Si pedimos 2 IDs pero solo encontramos 1,
         * alguna reserva no existe.
         */
        if (reservations.size()
                != reservationIds.size()) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Una o más reservas no existen"
            );
        }


        LocalDateTime now =
                LocalDateTime.now();


        // =====================================================
        // VALIDAMOS TODAS ANTES DE MODIFICAR EL STOCK
        // =====================================================

        for (
                InventoryReservation reservation
                : reservations
        ) {

            // La reserva debe seguir ACTIVE
            if (reservation.getStatus()
                    != ReservationStatus.ACTIVE) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La reserva "
                                + reservation.getId()
                                + " ya no está activa"
                );
            }


            // La reserva no puede estar vencida
            if (!reservation
                    .getExpiresAt()
                    .isAfter(now)) {

                reservation.setStatus(
                        ReservationStatus.EXPIRED
                );


                reservationRepository.save(
                        reservation
                );


                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La reserva "
                                + reservation.getId()
                                + " ha expirado"
                );
            }


            Product product =
                    reservation.getProduct();


            if (product == null) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "El producto asociado a la reserva "
                                + reservation.getId()
                                + " no existe"
                );
            }


            if (product.getStock() == null
                    || product.getStock()
                    < reservation.getQuantity()) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Stock insuficiente para "
                                + product.getName()
                );
            }
        }


        // =====================================================
        // TODAS SON VÁLIDAS:
        // DESCONTAR STOCK + CONFIRMAR
        // =====================================================

        for (
                InventoryReservation reservation
                : reservations
        ) {

            Product product =
                    reservation.getProduct();


            int newStock =
                    product.getStock()
                            - reservation.getQuantity();


            product.setStock(
                    newStock
            );


            productRepository.save(
                    product
            );


            reservation.setStatus(
                    ReservationStatus.CONFIRMED
            );
        }


        reservationRepository.saveAll(
                reservations
        );
    }


    // =========================================================
    // LIBERAR RESERVAS DESPUÉS DE PAGO RECHAZADO
    // =========================================================

    @Transactional
    public void releaseReservations(
            Long accountId, List<Long> reservationIds
    ) {
        BuyerAccess.requireAccountId(accountId);

        if (reservationIds == null
                || reservationIds.isEmpty()) {

            return;
        }


        List<InventoryReservation> reservations =
                ownedReservations(accountId, reservationIds);


        for (
                InventoryReservation reservation
                : reservations
        ) {

            /*
             * Solo liberamos reservas que todavía
             * estén activas.
             */
            if (reservation.getStatus()
                    == ReservationStatus.ACTIVE) {

                reservation.setStatus(
                        ReservationStatus.RELEASED
                );
            }
        }


        reservationRepository.saveAll(
                reservations
        );
    }


    @Transactional(readOnly = true)
    public void validateOwnership(Long accountId, List<Long> reservationIds) {
        ownedReservations(accountId, reservationIds);
    }

    private List<InventoryReservation> ownedReservations(Long accountId, List<Long> ids) {
        BuyerAccess.requireAccountId(accountId);
        if (ids == null || ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debe indicar las reservas a confirmar");
        }
        var reservations = reservationRepository.findByAccountIdAndIdIn(accountId, ids);
        if (reservations.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Una o más reservas no existen");
        }
        return reservations;
    }

    // =========================================================
    // EXPIRAR RESERVAS VENCIDAS
    // =========================================================

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


        if (!expired.isEmpty()) {

            reservationRepository.saveAll(
                    expired
            );
        }
    }


    // =========================================================
    // CONVERTIR ENTIDAD → RESPONSE
    // =========================================================

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