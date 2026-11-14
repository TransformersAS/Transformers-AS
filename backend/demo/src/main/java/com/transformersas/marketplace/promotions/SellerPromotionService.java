package com.transformersas.marketplace.promotions;

import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductRepository;
import com.transformersas.marketplace.product.ProductStatus;
import com.transformersas.marketplace.promotions.dto.PromotionRequest;
import com.transformersas.marketplace.promotions.dto.PromotionResponse;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.CheckStoreCommercialOperationUseCase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Diseñar promociones y cupones de venta (CU-16): el vendedor crea cupones de su tienda, elige a qué productos aplican,
 * define el descuento, la compra mínima y la vigencia, revisa los conflictos con sus otros cupones y luego los activa,
 * modifica, desactiva o elimina. Como en CU-14 y CU-15, cada método recibe la tienda del vendedor autenticado y solo
 * toca cupones de esa tienda. El descuento en la compra lo aplica {@link CheckoutCouponAspect}.
 */
@Service
@Transactional
public class SellerPromotionService {

    /** Cupón de prueba que ya reconoce el checkout (CU-03); un vendedor no puede reutilizar ese código. */
    static final String RESERVED_CODE = "DESC10";

    private static final BigDecimal MAX_PERCENT = new BigDecimal("100");

    private final PromotionRepository promotions;
    private final ProductRepository products;
    private final CheckStoreCommercialOperationUseCase storeOperation;

    public SellerPromotionService(PromotionRepository promotions, ProductRepository products,
                                  CheckStoreCommercialOperationUseCase storeOperation) {
        this.promotions = promotions;
        this.products = products;
        this.storeOperation = storeOperation;
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> list(Long storeId) {
        List<Promotion> all = promotions.findByStoreIdOrderByIdDesc(storeId);
        return all.stream().map(promotion -> toResponse(promotion, all)).toList();
    }

    public PromotionResponse create(Long storeId, PromotionRequest request) {
        storeOperation.requireForSeller(storeId);
        Promotion promotion = new Promotion(storeId);
        apply(promotion, request);
        return save(promotion);
    }

    public PromotionResponse update(Long storeId, Long id, PromotionRequest request) {
        storeOperation.requireForSeller(storeId);
        Promotion promotion = find(storeId, id);
        apply(promotion, request);
        return save(promotion);
    }

    public PromotionResponse activate(Long storeId, Long id) {
        storeOperation.requireForSeller(storeId);
        Promotion promotion = find(storeId, id);
        if (!promotion.getEndsAt().isAfter(LocalDateTime.now())) {
            throw BusinessException.conflict("PROMOTION_EXPIRED",
                    "El cupón ya terminó; cambia sus fechas antes de activarlo");
        }
        promotion.setActive(true);
        return save(promotion);
    }

    /** Desactivar siempre se permite, también a una tienda suspendida: es dejar de ofrecer un descuento. */
    public PromotionResponse deactivate(Long storeId, Long id) {
        Promotion promotion = find(storeId, id);
        promotion.setActive(false);
        return save(promotion);
    }

    public void delete(Long storeId, Long id) {
        promotions.delete(find(storeId, id));
    }

    // ---------- Reglas ----------

    private void apply(Promotion promotion, PromotionRequest request) {
        String code = request.code().strip().toUpperCase(Locale.ROOT);
        if (RESERVED_CODE.equals(code)) {
            throw BusinessException.conflict("PROMOTION_CODE_RESERVED", "El código DESC10 está reservado");
        }
        promotions.findByStoreIdAndCode(promotion.getStoreId(), code)
                .filter(other -> !other.getId().equals(promotion.getId()))
                .ifPresent(other -> {
                    throw BusinessException.conflict("PROMOTION_CODE_TAKEN",
                            "Ya tienes otro cupón con el código " + code);
                });
        if (request.discountType() == DiscountType.PERCENT && request.discountValue().compareTo(MAX_PERCENT) > 0) {
            throw BusinessException.invalid("PROMOTION_PERCENT_TOO_HIGH", "El porcentaje no puede ser mayor que 100");
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw BusinessException.invalid("PROMOTION_DATES", "La fecha de fin debe ser posterior a la de inicio");
        }
        promotion.setName(request.name().strip());
        promotion.setCode(code);
        promotion.setDiscountType(request.discountType());
        promotion.setDiscountValue(request.discountValue());
        promotion.setMinPurchase(request.minPurchase());
        promotion.setStartsAt(request.startsAt());
        promotion.setEndsAt(request.endsAt());
        promotion.setProductIds(checkedProducts(promotion.getStoreId(), request.productIds()));
    }

    /** Los productos elegidos deben ser de la tienda y no estar retirados. */
    private Set<Long> checkedProducts(Long storeId, Set<Long> productIds) {
        Set<Long> checked = new HashSet<>();
        if (productIds == null) {
            return checked;
        }
        for (Long productId : productIds) {
            Product product = products.findByIdAndStoreId(productId, storeId)
                    .filter(found -> found.getStatus() != ProductStatus.RETIRED)
                    .orElseThrow(() -> BusinessException.notFound("PROMOTION_PRODUCT_NOT_FOUND",
                            "El producto " + productId + " no existe en tu tienda"));
            checked.add(product.getId());
        }
        return checked;
    }

    private Promotion find(Long storeId, Long id) {
        return promotions.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> BusinessException.notFound("PROMOTION_NOT_FOUND", "Cupón no encontrado"));
    }

    private PromotionResponse save(Promotion promotion) {
        promotion.setUpdatedAt(LocalDateTime.now());
        Promotion saved = promotions.save(promotion);
        return toResponse(saved, promotions.findByStoreIdOrderByIdDesc(saved.getStoreId()));
    }

    private PromotionResponse toResponse(Promotion promotion, List<Promotion> storePromotions) {
        List<String> conflicts = storePromotions.stream()
                .filter(other -> !other.getId().equals(promotion.getId()))
                .filter(Promotion::isActive)
                .filter(promotion::overlaps)
                .map(other -> other.getName() + " (" + other.getCode() + ")")
                .toList();
        return new PromotionResponse(promotion.getId(), promotion.getName(), promotion.getCode(),
                promotion.getDiscountType(), promotion.getDiscountValue(), promotion.getMinPurchase(),
                promotion.getStartsAt(), promotion.getEndsAt(), promotion.isActive(), status(promotion),
                promotion.getProductIds().stream().sorted().toList(), conflicts);
    }

    private static String status(Promotion promotion) {
        LocalDateTime now = LocalDateTime.now();
        if (!promotion.isActive()) {
            return "INACTIVE";
        }
        if (!now.isBefore(promotion.getEndsAt())) {
            return "EXPIRED";
        }
        return now.isBefore(promotion.getStartsAt()) ? "SCHEDULED" : "RUNNING";
    }
}
