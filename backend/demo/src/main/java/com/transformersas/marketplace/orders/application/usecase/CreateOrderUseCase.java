/** Orquestación de los casos de uso de pedidos. */
package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.address.Address;
import com.transformersas.marketplace.address.AddressRepository;
import com.transformersas.marketplace.cart.Cart;
import com.transformersas.marketplace.cart.CartItem;
import com.transformersas.marketplace.cart.CartItemRepository;
import com.transformersas.marketplace.cart.CartRepository;

import com.transformersas.marketplace.orders.application.dto.OrderConfirmation;
import com.transformersas.marketplace.orders.domain.model.DeliverySnapshot;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatusHistoryEntry;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderStatusHistoryRepository;
import com.transformersas.marketplace.recommendation.interaction.InteractionService;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.web.CorrelationContext;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;



import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final AddressRepository addressRepository;
    private final UserAccountRepository accounts;
    private final InteractionService interactionService;

    public CreateOrderUseCase(
            OrderRepository orderRepository,
            OrderStatusHistoryRepository historyRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            AddressRepository addressRepository,
            UserAccountRepository accounts,
            InteractionService interactionService
    ) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.addressRepository = addressRepository;
        this.accounts = accounts;
        this.interactionService = interactionService;
    }

    @Transactional
    public OrderConfirmation execute(
            Long accountId,
            Long addressId,
            String shippingMethod,
            String transactionId,
            BigDecimal total
    ) {
        if (accountId == null || accountId <= 0 || accounts.findById(accountId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere un comprador válido");
        }

        Cart cart = cartRepository.findByAccountId(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No existe un carrito"));

        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());

        if (cartItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El carrito está vacío");
        }

        Long storeId = resolveStore(cartItems);
        DeliverySnapshot delivery = snapshotOf(accountId, addressId);

        List<OrderItem> orderItems = cartItems.stream()
                .map(item -> {
                    BigDecimal subtotal = item.getProduct().getPrice()
                            .multiply(BigDecimal.valueOf(item.getQuantity()));
                    return new OrderItem(
                            item.getProduct().getId(),
                            item.getProduct().getName(),
                            item.getQuantity(),
                            item.getProduct().getPrice(),
                            subtotal
                    );
                })
                .toList();

        LocalDateTime now = LocalDateTime.now();

        // El checkout solo llega aquí con el pago aprobado (D4); el comprador es la cuenta autenticada.
        Order order = new Order(null, accountId, OrderStatus.CONFIRMED, OrderPaymentStatus.APPROVED, total, storeId,
                addressId, shippingMethod, delivery, transactionId, now, orderItems);

        Order saved = orderRepository.save(order);

        historyRepository.append(new OrderStatusHistoryEntry(null, saved.id(), null, OrderStatus.CONFIRMED,
                ActorType.BUYER, accountId, "Compra confirmada", CorrelationContext.current(), now));

        // CU-02: registrar la compra para futuras recomendaciones
for (CartItem cartItem : cartItems) {
    interactionService.registerPurchase(
            accountId,
            cartItem.getProduct().getId()
    );
}

        // La compra ya quedó convertida en pedido: ahora sí se vacía el carrito.
        cartItemRepository.deleteAll(cartItems);

        return new OrderConfirmation(
                saved.id(),
                saved.status().name(),
                saved.transactionId(),
                saved.total(),
                saved.createdAt()
        );
    }

    /** El pedido pertenece a una sola tienda; un carrito multi-tienda se rechaza (dividirlo es de CU-03). */
    private Long resolveStore(List<CartItem> cartItems) {
        Set<Long> stores = cartItems.stream()
                .map(item -> item.getProduct().getStoreId())
                .collect(Collectors.toSet());
        if (stores.size() > 1) {
            throw BusinessException.conflict("MULTI_STORE_CART",
                    "El carrito contiene productos de varias tiendas; compra cada tienda por separado");
        }
        return stores.iterator().next();
    }

    /** Copia los datos de entrega vigentes: desde aquí el pedido no vuelve a leer la dirección del comprador. */
    private DeliverySnapshot snapshotOf(Long accountId, Long addressId) {
        Address address = addressRepository.findByIdAndAccountId(addressId, accountId)
                .orElseThrow(() -> BusinessException.notFound("ADDRESS_NOT_FOUND",
                        "La dirección seleccionada no existe"));
        return new DeliverySnapshot(address.getRecipientName(), address.getStreet(), address.getCity(),
                address.getDepartment(), address.getPostalCode(), address.getPhone());
    }
}
