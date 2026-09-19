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
 * Expone las publicaciones a soporte (CU-21). Los productos aún no registran vendedor, por eso no
 * hay propietario y no se puede pedir información al propietario de una publicación.
 */
@Component
class ProductContentSnapshotProvider implements ContentSnapshotProvider {

    private final ProductRepository productRepository;

    ProductContentSnapshotProvider(ProductRepository productRepository) {
        this.productRepository = productRepository;
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
            return new ContentSnapshot(product.getName(), product.getDescription(), null, attributes);
        });
    }
}
