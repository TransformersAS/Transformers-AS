package com.example.demo.cart.dto;

public record AddCartItemRequest(
        Long productId,
        Integer quantity
) {
}