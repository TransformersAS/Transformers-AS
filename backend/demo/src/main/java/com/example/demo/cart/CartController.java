package com.example.demo.cart;

import com.example.demo.cart.dto.AddCartItemRequest;
import com.example.demo.cart.dto.CartItemResponse;
import com.example.demo.cart.dto.CartResponse;
import com.example.demo.cart.dto.UpdateCartItemRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@CrossOrigin(origins = "*")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse getCart() {
        return cartService.getCart();
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartItemResponse addItem(
            @RequestBody AddCartItemRequest request
    ) {
        return cartService.addItem(request);
    }

    @PatchMapping("/items/{itemId}")
    public CartItemResponse updateQuantity(
            @PathVariable Long itemId,
            @RequestBody UpdateCartItemRequest request
    ) {
        return cartService.updateQuantity(itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(
            @PathVariable Long itemId
    ) {
        cartService.deleteItem(itemId);
    }
}