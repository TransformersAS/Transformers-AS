package com.transformersas.marketplace.payments.domain.model;

public record PaymentResult(
        String transactionId,
        PaymentStatus status,
        String message
) {
}