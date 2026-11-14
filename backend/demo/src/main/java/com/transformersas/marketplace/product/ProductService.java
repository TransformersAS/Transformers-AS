package com.transformersas.marketplace.product;

import com.transformersas.marketplace.product.dto.ProductRequest;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.product.dto.ProductResponse;
import com.transformersas.marketplace.reports.application.ContentVisibility;
import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.stores.application.usecase.CheckStoreCommercialOperationUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ProductService {
    /** Estados que ve un comprador: ni borradores ni retirados por su vendedor (CU-14). */
    private static final Set<ProductStatus> VISIBLE_TO_BUYERS = EnumSet.of(ProductStatus.ACTIVE, ProductStatus.PAUSED);
    /** Id que ningún producto ni tienda tiene: una lista vacía en un NOT IN de SQL no devolvería filas. */
    private static final Set<Long> NONE = Set.of(-1L);

    private final CurrentActorProvider actor;
    private final ProductRepository productRepository;
    private final ContentVisibility contentVisibility;
    private final CheckStoreCommercialOperationUseCase storeOperation;
    private final com.transformersas.marketplace.reviews.application.ReviewService reviews;

    public ProductService(ProductRepository productRepository, ContentVisibility contentVisibility, CurrentActorProvider actor,
                          CheckStoreCommercialOperationUseCase storeOperation, com.transformersas.marketplace.reviews.application.ReviewService reviews) {
        this.actor = actor;
        this.productRepository = productRepository;
        this.contentVisibility = contentVisibility;
        this.storeOperation = storeOperation;
        this.reviews = reviews;
    }

    /**
     * No se listan las publicaciones ocultas o retiradas por moderación (CU-21) ni las de tiendas que no pueden recibir
     * nuevas compras, como una suspendida (CU-27).
     */
    public List<ProductResponse> getAllProducts() {
        Set<Long> hidden = new HashSet<>(NONE);
        for (String id : contentVisibility.nonVisibleIds(ReportContentType.PUBLICACION)) {
            // Un id de contenido que no sea numérico no corresponde a ningún producto.
            if (id.matches("[0-9]{1,18}")) {
                hidden.add(Long.valueOf(id));
            }
        }
        Set<Long> closedStores = new HashSet<>(NONE);
        closedStores.addAll(storeOperation.nonOperatingStoreIds());
        var ratings = reviews.summaries(null, null);
        return productRepository.findCatalog(VISIBLE_TO_BUYERS, hidden, closedStores).stream()
                .map(product -> {
                    var rating = ratings.get(product.id());
                    return rating == null ? product : product.withRating(rating.average(), rating.count());
                }).toList();
    }

    /** Los borradores y los productos retirados por su vendedor (CU-14) nunca se muestran a los compradores. */
    private boolean isVisibleToBuyers(Product product) {
        return VISIBLE_TO_BUYERS.contains(product.getStatus());
    }

    public ProductResponse getProductById(Long id) {
        if (contentVisibility.stateOf(ReportContentType.PUBLICACION, String.valueOf(id)) != ContentModerationState.VISIBLE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado");
        }
        return productRepository.findById(id).filter(this::isVisibleToBuyers).map(p->{var rating=reviews.summary(id);return ProductResponse.from(p).withRating(rating.average(),rating.count());})
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        Long storeId = actor.storeId();
        storeOperation.requireForSeller(storeId);
        Product product = new Product();
        product.setStoreId(storeId);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setCategory(request.category());
        boolean active = request.active() == null || request.active();
        product.changeStatus(active ? ProductStatus.ACTIVE : ProductStatus.PAUSED);
        return ProductResponse.from(productRepository.save(product));
    }
}
