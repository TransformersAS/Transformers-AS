package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.orders.domain.port.OrderReceiptPdfGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
public class GenerateBuyerOrderReceiptUseCase {
    private final FindOwnOrders orders;
    private final OrderReceiptPdfGenerator pdf;

    public GenerateBuyerOrderReceiptUseCase(FindOwnOrders orders, OrderReceiptPdfGenerator pdf) {
        this.orders = orders;
        this.pdf = pdf;
    }

    @Transactional(readOnly = true)
    public byte[] execute(Long id, AccountPrincipal principal) {
        var order = orders.detail(id, principal);
        return pdf.generate(order, ZonedDateTime.now(ZoneId.of("America/Bogota")));
    }
}
