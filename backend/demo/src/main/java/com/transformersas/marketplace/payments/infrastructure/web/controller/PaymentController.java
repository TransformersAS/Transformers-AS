package com.transformersas.marketplace.payments.infrastructure.web.controller;

import com.transformersas.marketplace.payments.application.dto.PaymentProcessResult;
import com.transformersas.marketplace.payments.application.usecase.ProcessPaymentUseCase;
import com.transformersas.marketplace.payments.infrastructure.web.request.PaymentRequest;
import com.transformersas.marketplace.payments.infrastructure.web.response.PaymentResponse;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
            @RequestBody PaymentRequest request,
            @AuthenticationPrincipal AccountPrincipal principal
    ) {

        if (principal == null || principal.accountId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere un comprador autenticado");
        }

        PaymentProcessResult result =
                processPaymentUseCase.execute(
                        principal.accountId(),
                        request.paymentMethod(),
                        request.reservationIds(),
                        request.addressId(),
                        request.shippingMethod(),
                        request.couponCode()
                );


        return ResponseEntity.ok(
                PaymentResponse.from(
                        result
                )
        );
    }
}