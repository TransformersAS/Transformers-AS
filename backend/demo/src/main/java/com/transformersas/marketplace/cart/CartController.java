package com.transformersas.marketplace.cart;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.auth.infrastructure.security.BuyerAccess;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.transformersas.marketplace.cart.dto.AddCartItemRequest;
import com.transformersas.marketplace.cart.dto.CartItemResponse;
import com.transformersas.marketplace.cart.dto.CartResponse;
import com.transformersas.marketplace.cart.dto.UpdateCartItemRequest;

@RestController
@RequestMapping("/api/cart")
@CrossOrigin(origins = "*")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse getCart(@AuthenticationPrincipal AccountPrincipal principal) {
        return cartService.getCart(BuyerAccess.accountId(principal));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartItemResponse addItem(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody AddCartItemRequest request
    ) {
        return cartService.addItem(BuyerAccess.accountId(principal), request);
    }

    @PatchMapping("/items/{itemId}")
    public CartItemResponse updateQuantity(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateCartItemRequest request
    ) {
        return cartService.updateQuantity(BuyerAccess.accountId(principal), itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long itemId
    ) {
        cartService.deleteItem(BuyerAccess.accountId(principal), itemId);
    }
}