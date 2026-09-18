package com.transformersas.marketplace.payments.infrastructure.web.response;

import com.transformersas.marketplace.orders.application.dto.OrderConfirmation;
import com.transformersas.marketplace.payments.application.dto.PaymentProcessResult;
import com.transformersas.marketplace.payments.domain.model.PaymentResult;

import java.math.BigDecimal;

public record PaymentResponse(
        String transactionId,
        String status,
        String message,
        Long orderId,
        BigDecimal total
) {

    public static PaymentResponse from(
            PaymentProcessResult result
    ) {

        PaymentResult payment =
                result.payment();


        OrderConfirmation order =
                result.order();


        return new PaymentResponse(
                payment.transactionId(),
                payment.status().name(),
                payment.message(),

                order != null
                        ? order.orderId()
                        : null,

                order != null
                        ? order.total()
                        : null
        );
    }
}