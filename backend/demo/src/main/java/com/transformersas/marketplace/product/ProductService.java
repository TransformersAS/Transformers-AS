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
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ProductService {
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
        Set<String> moderated = contentVisibility.nonVisibleIds(ReportContentType.PUBLICACION);
        Set<Long> closedStores = storeOperation.nonOperatingStoreIds();
        var ratings=reviews.summaries(null,null);
        return productRepository.findAll().stream()
                .filter(this::isVisibleToBuyers)
                .filter(product -> !moderated.contains(String.valueOf(product.getId())))
                .filter(product -> !closedStores.contains(product.getStoreId()))
                .map(p->{var rating=ratings.get(p.getId());return ProductResponse.from(p).withRating(rating==null?0:rating.average(),rating==null?0:rating.count());}).toList();
    }

    /** Los borradores y los productos retirados por su vendedor (CU-14) nunca se muestran a los compradores. */
    private boolean isVisibleToBuyers(Product product) {
        return product.getStatus() != ProductStatus.DRAFT && product.getStatus() != ProductStatus.RETIRED;
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
