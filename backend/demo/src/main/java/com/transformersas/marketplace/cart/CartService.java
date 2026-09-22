package com.transformersas.marketplace.cart;

import com.transformersas.marketplace.auth.infrastructure.security.BuyerAccess;
import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductRepository;
import com.transformersas.marketplace.cart.dto.AddCartItemRequest;
import com.transformersas.marketplace.cart.dto.CartItemResponse;
import com.transformersas.marketplace.cart.dto.CartResponse;
import com.transformersas.marketplace.cart.dto.UpdateCartItemRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;

    public CartService(
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            ProductRepository productRepository
    ) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
    }

    private Cart getOrCreateCart(Long accountId) {
        return cartRepository.findByAccountId(BuyerAccess.requireAccountId(accountId))
                .orElseGet(() -> {
                    Cart cart = new Cart();
                    cart.setAccountId(accountId);
                    return cartRepository.save(cart);
                });
    }

    // This read also creates the account cart when absent, so it needs a write transaction.
    public CartResponse getCart(Long accountId) {

        Cart cart = getOrCreateCart(accountId);

        List<CartItemResponse> items =
                cartItemRepository.findByCartId(cart.getId())
                        .stream()
                        .map(this::toResponse)
                        .toList();

        BigDecimal total = items.stream()
                .map(CartItemResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartResponse(
                cart.getId(),
                items,
                total
        );
    }

    public CartItemResponse addItem(Long accountId, AddCartItemRequest request) {

        if (request.quantity() == null || request.quantity() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La cantidad debe ser mayor que cero"
            );
        }

        Product product = productRepository
                .findById(request.productId())
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Producto no encontrado"
                        )
                );

        if (!product.getActive()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El producto no está disponible"
            );
        }

        Cart cart = getOrCreateCart(accountId);

        CartItem item = cartItemRepository
                .findByCartIdAndProductId(
                        cart.getId(),
                        product.getId()
                )
                .orElse(null);

        long newQuantity =
                (long) request.quantity()
                + (item == null ? 0 : item.getQuantity());

        if (newQuantity > product.getStock()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Stock insuficiente"
            );
        }

        if (item == null) {
            item = new CartItem();
            item.setCart(cart);
            item.setProduct(product);
        }

        item.setQuantity((int) newQuantity);

        return toResponse(
                cartItemRepository.save(item)
        );
    }

    public CartItemResponse updateQuantity(
            Long accountId,
            Long itemId,
            UpdateCartItemRequest request
    ) {

        CartItem item = cartItemRepository
                .findByIdAndCart_AccountId(itemId, BuyerAccess.requireAccountId(accountId))
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Elemento del carrito no encontrado"
                        )
                );

        if (request.quantity() == null || request.quantity() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La cantidad debe ser mayor que cero"
            );
        }

        if (!item.getProduct().getActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El producto no está disponible");
        }

        if (request.quantity() > item.getProduct().getStock()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Stock insuficiente"
            );
        }

        item.setQuantity(request.quantity());

        return toResponse(
                cartItemRepository.save(item)
        );
    }

    public void deleteItem(Long accountId, Long itemId) {
        CartItem item = cartItemRepository.findByIdAndCart_AccountId(itemId, BuyerAccess.requireAccountId(accountId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Elemento del carrito no encontrado"));
        cartItemRepository.delete(item);
    }

    private CartItemResponse toResponse(CartItem item) {

        BigDecimal subtotal =
                item.getProduct()
                        .getPrice()
                        .multiply(
                                BigDecimal.valueOf(item.getQuantity())
                        );

        return new CartItemResponse(
                item.getId(),
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getQuantity(),
                item.getProduct().getPrice(),
                subtotal
        );
    }
}