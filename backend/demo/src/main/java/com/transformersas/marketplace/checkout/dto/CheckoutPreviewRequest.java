package com.transformersas.marketplace.checkout.dto;

public record CheckoutPreviewRequest(
        Long addressId,
        String shippingMethod,
        String couponCode
) {
}