package com.transformersas.marketplace.orders.domain.port;

import com.transformersas.marketplace.orders.domain.model.Order;
import java.time.ZonedDateTime;

public interface OrderReceiptPdfGenerator {
    byte[] generate(Order authorizedOrder, ZonedDateTime generatedAt);
}
