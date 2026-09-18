package com.transformersas.marketplace.payments.infrastructure.gateway;

import com.transformersas.marketplace.payments.domain.model.PaymentResult;
import com.transformersas.marketplace.payments.domain.model.PaymentStatus;
import com.transformersas.marketplace.payments.domain.repository.PaymentGateway;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
public class MockPaymentGateway
        implements PaymentGateway {

    @Override
    public PaymentResult process(
            String paymentMethod
    ) {

        if (paymentMethod == null
                || paymentMethod.isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debe seleccionar un método de pago"
            );
        }


        String method =
                paymentMethod
                        .trim()
                        .toUpperCase();


        String transactionId =
                UUID.randomUUID()
                        .toString();


        return switch (method) {

            case "CARD" ->
                    new PaymentResult(
                            transactionId,
                            PaymentStatus.APPROVED,
                            "Pago aprobado"
                    );


            case "TEST_REJECT" ->
                    new PaymentResult(
                            transactionId,
                            PaymentStatus.REJECTED,
                            "El pago fue rechazado"
                    );


            case "TEST_PENDING" ->
                    new PaymentResult(
                            transactionId,
                            PaymentStatus.PENDING,
                            "El pago se encuentra pendiente"
                    );


            case "TEST_UNAVAILABLE" ->
                    throw new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "La pasarela de pago no está disponible"
                    );


            default ->
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Método de pago inválido"
                    );
        };
    }
}