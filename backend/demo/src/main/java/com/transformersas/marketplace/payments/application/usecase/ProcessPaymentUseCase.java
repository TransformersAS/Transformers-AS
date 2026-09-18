package com.transformersas.marketplace.payments.application.usecase;

import com.transformersas.marketplace.payments.domain.model.PaymentResult;
import com.transformersas.marketplace.payments.domain.model.PaymentStatus;
import com.transformersas.marketplace.payments.domain.repository.PaymentGateway;

import com.transformersas.marketplace.reservation.InventoryReservationService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProcessPaymentUseCase {

    private final PaymentGateway paymentGateway;

    private final InventoryReservationService
            reservationService;


    public ProcessPaymentUseCase(
            PaymentGateway paymentGateway,
            InventoryReservationService reservationService
    ) {

        this.paymentGateway =
                paymentGateway;

        this.reservationService =
                reservationService;
    }


    @Transactional
    public PaymentResult execute(
            String paymentMethod,
            List<Long> reservationIds
    ) {

        PaymentResult result =
                paymentGateway.process(
                        paymentMethod
                );


        if (result.status()
                == PaymentStatus.APPROVED) {

            reservationService
                    .confirmReservations(
                            reservationIds
                    );
        }


        if (result.status()
                == PaymentStatus.REJECTED) {

            reservationService
                    .releaseReservations(
                            reservationIds
                    );
        }


        /*
         * PENDING:
         *
         * No hacemos nada.
         * La reserva permanece ACTIVE
         * hasta que el pago se resuelva
         * o expire.
         */


        return result;
    }
}