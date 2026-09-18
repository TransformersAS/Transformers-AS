package com.transformersas.marketplace.payments.infrastructure.web.request;

public record PaymentRequest(
        String paymentMethod
) {
}