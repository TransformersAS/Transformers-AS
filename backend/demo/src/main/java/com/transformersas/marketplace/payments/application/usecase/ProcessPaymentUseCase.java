package com.transformersas.marketplace.payments.application.usecase;

import com.transformersas.marketplace.payments.domain.model.PaymentResult;
import com.transformersas.marketplace.payments.domain.repository.PaymentGateway;

import org.springframework.stereotype.Service;

@Service
public class ProcessPaymentUseCase {

    private final PaymentGateway paymentGateway;

    public ProcessPaymentUseCase(
            PaymentGateway paymentGateway
    ) {
        this.paymentGateway = paymentGateway;
    }

    public PaymentResult execute(
            String paymentMethod
    ) {

        return paymentGateway.process(
                paymentMethod
        );
    }
}