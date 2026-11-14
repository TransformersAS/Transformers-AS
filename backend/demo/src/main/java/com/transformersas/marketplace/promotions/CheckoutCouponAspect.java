package com.transformersas.marketplace.promotions;

import com.transformersas.marketplace.checkout.dto.CheckoutPreviewRequest;
import com.transformersas.marketplace.checkout.dto.CheckoutPreviewResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Conecta los cupones de vendedor (CU-16) con el checkout (CU-03) sin modificar CheckoutService.
 *
 * <p>Envuelve {@code CheckoutService.preview(accountId, request)}: primero corre el checkout tal cual (valida carrito,
 * tienda, envío y su cupón de prueba DESC10). Si el comprador escribió un código y el checkout lo dio por inválido, se
 * busca entre los cupones de la tienda del carrito; si aplica, se devuelve la misma vista previa con el descuento y el
 * total corregidos. Los tres caminos que cobran (vista previa, pago simulado y Mercado Pago) recalculan el total con
 * preview(), así que el pedido guarda este mismo descuento en orders.discount_amount.
 *
 * <p>Si alguien cambia el nombre o los parámetros de preview(), este aspecto deja de aplicarse sin error:
 * CheckoutCouponTests lo detecta.
 */
@Aspect
@Component
public class CheckoutCouponAspect {

    private final CouponCalculator coupons;

    public CheckoutCouponAspect(CouponCalculator coupons) {
        this.coupons = coupons;
    }

    @Around("execution(com.transformersas.marketplace.checkout.dto.CheckoutPreviewResponse "
            + "com.transformersas.marketplace.checkout.CheckoutService.preview(Long, "
            + "com.transformersas.marketplace.checkout.dto.CheckoutPreviewRequest)) && args(accountId, request)")
    public Object applySellerCoupon(ProceedingJoinPoint call, Long accountId, CheckoutPreviewRequest request)
            throws Throwable {
        CheckoutPreviewResponse preview = (CheckoutPreviewResponse) call.proceed();
        if (preview == null || preview.couponValid() || request == null) {
            return preview;
        }
        return coupons.discountFor(accountId, request.couponCode())
                .map(discount -> new CheckoutPreviewResponse(preview.subtotal(), discount, preview.shippingCost(),
                        preview.subtotal().subtract(discount).add(preview.shippingCost()), true))
                .orElse(preview);
    }
}
