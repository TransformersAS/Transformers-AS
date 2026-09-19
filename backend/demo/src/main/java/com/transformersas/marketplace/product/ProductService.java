package com.transformersas.marketplace.product;

import com.transformersas.marketplace.product.dto.ProductRequest;
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
    private final ProductRepository productRepository;
    private final ContentVisibility contentVisibility;

    public ProductService(ProductRepository productRepository, ContentVisibility contentVisibility) {
        this.productRepository = productRepository;
        this.contentVisibility = contentVisibility;
    }

    /** Las publicaciones ocultas o retiradas por moderación (CU-21) no se listan. */
    public List<ProductResponse> getAllProducts() {
        Set<String> moderated = contentVisibility.nonVisibleIds(ReportContentType.PUBLICACION);
        return productRepository.findAll().stream()
                .filter(product -> !moderated.contains(String.valueOf(product.getId())))
                .map(ProductResponse::from).toList();
    }

    public ProductResponse getProductById(Long id) {
        if (contentVisibility.stateOf(ReportContentType.PUBLICACION, String.valueOf(id)) != ContentModerationState.VISIBLE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado");
        }
        return productRepository.findById(id).map(ProductResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        Product product = new Product();
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setCategory(request.category());
        product.setActive(request.active() == null ? true : request.active());
        return ProductResponse.from(productRepository.save(product));
    }
}
