package com.transformersas.marketplace.promotions;

import com.transformersas.marketplace.cart.CartItem;
import com.transformersas.marketplace.cart.CartItemRepository;
import com.transformersas.marketplace.cart.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Calcula cuánto descuenta un cupón de vendedor (CU-16) sobre el carrito del comprador. El checkout ya validó el
 * carrito (una sola tienda, productos activos, stock) antes de que esto se ejecute; aquí solo se mira el cupón.
 */
@Service
@Transactional(readOnly = true)
public class CouponCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final PromotionRepository promotions;

    public CouponCalculator(CartRepository carts, CartItemRepository cartItems, PromotionRepository promotions) {
        this.carts = carts;
        this.cartItems = cartItems;
        this.promotions = promotions;
    }

    /**
     * Descuento del cupón para el carrito de la cuenta, o vacío si el código no existe en la tienda del carrito, no
     * está vigente, no aplica a ninguno de sus productos o no se alcanza la compra mínima.
     */
    public Optional<BigDecimal> discountFor(Long accountId, String couponCode) {
        if (couponCode == null || couponCode.isBlank()) {
            return Optional.empty();
        }
        List<CartItem> items = carts.findByAccountId(accountId)
                .map(cart -> cartItems.findByCartId(cart.getId()))
                .orElse(List.of());
        if (items.isEmpty()) {
            return Optional.empty();
        }
        Long storeId = items.get(0).getProduct().getStoreId();
        String code = couponCode.strip().toUpperCase(Locale.ROOT);
        return promotions.findByStoreIdAndCode(storeId, code)
                .filter(promotion -> promotion.isRunningAt(LocalDateTime.now()))
                .flatMap(promotion -> discount(promotion, items));
    }

    private static Optional<BigDecimal> discount(Promotion promotion, List<CartItem> items) {
        BigDecimal applicable = items.stream()
                .filter(item -> promotion.appliesTo(item.getProduct().getId()))
                .map(item -> item.getProduct().getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (applicable.signum() == 0) {
            return Optional.empty();
        }
        if (promotion.getMinPurchase() != null && applicable.compareTo(promotion.getMinPurchase()) < 0) {
            return Optional.empty();
        }
        BigDecimal value = promotion.getDiscountType() == DiscountType.PERCENT
                ? applicable.multiply(promotion.getDiscountValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                : promotion.getDiscountValue().min(applicable);
        return Optional.of(value.setScale(2, RoundingMode.HALF_UP));
    }
}
