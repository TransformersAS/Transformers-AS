package com.transformersas.marketplace.payments.application.usecase;

import com.transformersas.marketplace.checkout.CheckoutService;
import com.transformersas.marketplace.checkout.dto.CheckoutPreviewRequest;
import com.transformersas.marketplace.checkout.dto.CheckoutPreviewResponse;

import com.transformersas.marketplace.orders.application.dto.OrderConfirmation;
import com.transformersas.marketplace.orders.application.usecase.CreateOrderUseCase;

import com.transformersas.marketplace.payments.application.dto.PaymentProcessResult;
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

    private final InventoryReservationService reservationService;

    private final CheckoutService checkoutService;

    private final CreateOrderUseCase createOrderUseCase;


    public ProcessPaymentUseCase(
            PaymentGateway paymentGateway,
            InventoryReservationService reservationService,
            CheckoutService checkoutService,
            CreateOrderUseCase createOrderUseCase
    ) {

        this.paymentGateway =
                paymentGateway;

        this.reservationService =
                reservationService;

        this.checkoutService =
                checkoutService;

        this.createOrderUseCase =
                createOrderUseCase;
    }


    @Transactional
    public PaymentProcessResult execute(
            Long accountId,
            String paymentMethod,
            List<Long> reservationIds,
            Long addressId,
            String shippingMethod,
            String couponCode
    ) {

        /*
         * Recalculamos todo en backend.
         * Nunca confiamos en el total enviado
         * desde el frontend.
         */
        CheckoutPreviewResponse preview =
                checkoutService.preview(
                        new CheckoutPreviewRequest(
                                addressId,
                                shippingMethod,
                                couponCode
                        )
                );


        /*
         * Se contacta la pasarela.
         */
        PaymentResult payment =
                paymentGateway.process(
                        paymentMethod
                );


        OrderConfirmation order =
                null;


        // =========================
        // APPROVED
        // =========================

        if (payment.status()
                == PaymentStatus.APPROVED) {

            /*
             * Confirmamos reservas
             * y descontamos stock.
             */
            reservationService
                    .confirmReservations(
                            reservationIds
                    );


            /*
             * Creamos pedido CONFIRMED.
             *
             * CreateOrderUseCase también
             * vacía el carrito.
             */
            order =
                    createOrderUseCase.execute(
                            accountId,
                            addressId,
                            shippingMethod,
                            payment.transactionId(),
                            preview.total()
                    );
        }


        // =========================
        // REJECTED
        // =========================

        if (payment.status()
                == PaymentStatus.REJECTED) {

            /*
             * El stock nunca fue disminuido.
             *
             * Solo liberamos las reservas.
             */
            reservationService
                    .releaseReservations(
                            reservationIds
                    );
        }


        /*
         * PENDING:
         *
         * no confirmamos
         * no liberamos
         *
         * la reserva sigue ACTIVE.
         */


        return new PaymentProcessResult(
                payment,
                order
        );
    }
}