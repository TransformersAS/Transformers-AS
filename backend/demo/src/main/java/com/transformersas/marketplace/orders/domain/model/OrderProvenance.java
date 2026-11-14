package com.transformersas.marketplace.orders.domain.model;

/** Origen de los datos; capturado no equivale a acreditación externa. */
public record OrderProvenance(Source deliverySource, Source sellerSource, Source paymentSource,
                              OrderPaymentStatus paymentStatusAtCapture, boolean financialUpdateVerified) {
    public OrderProvenance {
        if (deliverySource == null) deliverySource = Source.UNKNOWN;
        if (sellerSource == null) sellerSource = Source.UNKNOWN;
        if (paymentSource == null) paymentSource = Source.UNKNOWN;
    }
    public enum Source { CAPTURED_AT_PURCHASE, MIGRATED, UNKNOWN }
    public static OrderProvenance unknown() { return new OrderProvenance(Source.UNKNOWN, Source.UNKNOWN, Source.UNKNOWN, null, false); }
    public static OrderProvenance captured() { return new OrderProvenance(Source.CAPTURED_AT_PURCHASE,
            Source.CAPTURED_AT_PURCHASE, Source.CAPTURED_AT_PURCHASE, OrderPaymentStatus.APPROVED, false); }
    public Source currentPaymentSource(OrderPaymentStatus current) {
        return current == paymentStatusAtCapture ? paymentSource : Source.UNKNOWN;
    }
    public OrderProvenance withVerifiedUpdate() {
        return new OrderProvenance(deliverySource, sellerSource, paymentSource, paymentStatusAtCapture, true);
    }
}
