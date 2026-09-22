package com.transformersas.marketplace.product;

import com.transformersas.marketplace.product.dto.ProductRequest;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.product.dto.ProductResponse;
import com.transformersas.marketplace.reports.application.ContentVisibility;
import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
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

    public ProductService(ProductRepository productRepository, ContentVisibility contentVisibility, CurrentActorProvider actor) {
        this.actor = actor;
        this.productRepository = productRepository;
        this.contentVisibility = contentVisibility;
    }

    /** Las publicaciones ocultas o retiradas por moderación (CU-21) no se listan. */
    public List<ProductResponse> getAllProducts() {
        Set<String> moderated = contentVisibility.nonVisibleIds(ReportContentType.PUBLICACION);
        return productRepository.findAll().stream()
                .filter(this::isVisibleToBuyers)
                .filter(product -> !moderated.contains(String.valueOf(product.getId())))
                .map(ProductResponse::from).toList();
    }

    /** Los borradores y los productos retirados por su vendedor (CU-14) nunca se muestran a los compradores. */
    private boolean isVisibleToBuyers(Product product) {
        return product.getStatus() != ProductStatus.DRAFT && product.getStatus() != ProductStatus.RETIRED;
    }

    public ProductResponse getProductById(Long id) {
        if (contentVisibility.stateOf(ReportContentType.PUBLICACION, String.valueOf(id)) != ContentModerationState.VISIBLE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado");
        }
        return productRepository.findById(id).filter(this::isVisibleToBuyers).map(ProductResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        Long storeId = actor.storeId();
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
