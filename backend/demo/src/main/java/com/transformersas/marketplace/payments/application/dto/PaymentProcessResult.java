package com.transformersas.marketplace.payments.application.dto;

import com.transformersas.marketplace.orders.application.dto.OrderConfirmation;
import com.transformersas.marketplace.payments.domain.model.PaymentResult;

public record PaymentProcessResult(
        PaymentResult payment,
        OrderConfirmation order
) {
}