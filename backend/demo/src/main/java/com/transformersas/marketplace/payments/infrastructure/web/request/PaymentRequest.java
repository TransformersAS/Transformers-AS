package com.transformersas.marketplace.payments.infrastructure.web.request;

import java.util.List;

public record PaymentRequest(
        String paymentMethod,
        List<Long> reservationIds,
        Long addressId,
        String shippingMethod,
        String couponCode
) {
}