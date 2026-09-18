package com.transformersas.marketplace.cart.dto;

public record AddCartItemRequest(
        Long productId,
        Integer quantity
) {
}