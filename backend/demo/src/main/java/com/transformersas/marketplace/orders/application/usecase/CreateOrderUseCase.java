/** Orquestación de los casos de uso de pedidos. */
package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.cart.Cart;
import com.transformersas.marketplace.cart.CartItem;
import com.transformersas.marketplace.cart.CartItemRepository;
import com.transformersas.marketplace.cart.CartRepository;

import com.transformersas.marketplace.orders.application.dto.OrderConfirmation;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;

import com.transformersas.marketplace.recommendation.interaction.InteractionService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CreateOrderUseCase {

    private final OrderRepository orderRepository;

    private final CartRepository cartRepository;

    private final CartItemRepository cartItemRepository;

    private final InteractionService interactionService;


    public CreateOrderUseCase(
            OrderRepository orderRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            InteractionService interactionService
    ) {

        this.orderRepository =
                orderRepository;

        this.cartRepository =
                cartRepository;

        this.cartItemRepository =
                cartItemRepository;

        this.interactionService =
                interactionService;
    }


    @Transactional
    public OrderConfirmation execute(
            Long addressId,
            String shippingMethod,
            String transactionId,
            BigDecimal total
    ) {

        Cart cart =
                cartRepository
                        .findAll()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "No existe un carrito"
                                        )
                        );


        List<CartItem> cartItems =
                cartItemRepository
                        .findByCartId(
                                cart.getId()
                        );


        if (cartItems.isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El carrito está vacío"
            );
        }


        List<OrderItem> orderItems =
                cartItems
                        .stream()
                        .map(
                                item -> {

                                    BigDecimal subtotal =
                                            item.getProduct()
                                                    .getPrice()
                                                    .multiply(
                                                            BigDecimal.valueOf(
                                                                    item.getQuantity()
                                                            )
                                                    );


                                    return new OrderItem(
                                            item.getProduct().getId(),
                                            item.getProduct().getName(),
                                            item.getQuantity(),
                                            item.getProduct().getPrice(),
                                            subtotal
                                    );
                                }
                        )
                        .toList();


        Order order =
                new Order(
                        null,
                        OrderStatus.CONFIRMED,
                        total,
                        addressId,
                        shippingMethod,
                        transactionId,
                        LocalDateTime.now(),
                        orderItems
                );


        Order saved =
                orderRepository.save(
                        order
                );


        /*
         * El pedido ya fue creado correctamente.
         *
         * Registramos cada producto comprado
         * como una interacción PURCHASE
         * para el sistema de recomendaciones.
         */
        for (CartItem cartItem : cartItems) {

            interactionService.registerPurchase(
                    1L,
                    cartItem.getProduct().getId()
            );
        }


        /*
         * Después de registrar la compra,
         * vaciamos el carrito.
         */
        cartItemRepository.deleteAll(
                cartItems
        );


        return new OrderConfirmation(
                saved.id(),
                saved.status().name(),
                saved.transactionId(),
                saved.total(),
                saved.createdAt()
        );
    }
}