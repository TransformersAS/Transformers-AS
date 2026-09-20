package com.transformersas.marketplace.product;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.port.ContentSnapshot;
import com.transformersas.marketplace.reports.domain.port.ContentSnapshotProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Expone las publicaciones a soporte (CU-21). El propietario es la dueña de la tienda del producto; si la tienda
 * aún no tiene dueña, no hay propietario y no se le puede notificar ni pedirle información.
 */
@Component
class ProductContentSnapshotProvider implements ContentSnapshotProvider {

    private final ProductRepository productRepository;
    private final ProductContentOwnerResolver owners;

    ProductContentSnapshotProvider(ProductRepository productRepository, ProductContentOwnerResolver owners) {
        this.productRepository = productRepository;
        this.owners = owners;
    }

    @Override
    public ReportContentType type() {
        return ReportContentType.PUBLICACION;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContentSnapshot> load(String contentId) {
        Long id;
        try {
            id = Long.valueOf(contentId);
        } catch (NumberFormatException notAProductId) {
            return Optional.empty();
        }
        return productRepository.findById(id).map(product -> {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("category", product.getCategory());
            attributes.put("price", product.getPrice().toPlainString());
            attributes.put("stock", String.valueOf(product.getStock()));
            attributes.put("active", String.valueOf(product.getActive()));
            String ownerId = owners.ownerAccountId(contentId).orElse(null);
            return new ContentSnapshot(product.getName(), product.getDescription(), ownerId, attributes);
        });
    }
}
