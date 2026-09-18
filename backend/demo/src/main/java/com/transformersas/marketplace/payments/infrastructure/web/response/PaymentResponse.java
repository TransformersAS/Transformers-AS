package com.transformersas.marketplace.payments.infrastructure.web.response;

import com.transformersas.marketplace.payments.domain.model.PaymentResult;

public record PaymentResponse(
        String transactionId,
        String status,
        String message
) {

    public static PaymentResponse from(
            PaymentResult result
    ) {

        return new PaymentResponse(
                result.transactionId(),
                result.status().name(),
                result.message()
        );
    }
}