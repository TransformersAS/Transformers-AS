package com.transformersas.marketplace.payments.infrastructure.web.controller;

import com.transformersas.marketplace.payments.application.usecase.ProcessPaymentUseCase;
import com.transformersas.marketplace.payments.domain.model.PaymentResult;
import com.transformersas.marketplace.payments.infrastructure.web.request.PaymentRequest;
import com.transformersas.marketplace.payments.infrastructure.web.response.PaymentResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "*")
public class PaymentController {

    private final ProcessPaymentUseCase processPaymentUseCase;


    public PaymentController(
            ProcessPaymentUseCase processPaymentUseCase
    ) {

        this.processPaymentUseCase =
                processPaymentUseCase;
    }


    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> process(
            @RequestBody PaymentRequest request
    ) {

        PaymentResult result =
                processPaymentUseCase.execute(
                        request.paymentMethod()
                );


        return ResponseEntity.ok(
                PaymentResponse.from(
                        result
                )
        );
    }
}