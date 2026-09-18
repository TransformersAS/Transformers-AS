package com.transformersas.marketplace.payments.domain.repository;

import com.transformersas.marketplace.payments.domain.model.PaymentResult;

public interface PaymentGateway {

    PaymentResult process(
            String paymentMethod
    );
}