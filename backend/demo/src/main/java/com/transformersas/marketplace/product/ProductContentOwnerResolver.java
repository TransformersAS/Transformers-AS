package com.transformersas.marketplace.product;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.port.ContentOwnerResolver;
import com.transformersas.marketplace.stores.application.usecase.FindStoreOwnerUseCase;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** El propietario de una publicación es la dueña de su tienda: {@code products.store_id} → {@code stores.owner_account_id}. */
@Component
class ProductContentOwnerResolver implements ContentOwnerResolver {
    private final ProductRepository productRepository;
    private final FindStoreOwnerUseCase storeOwner;

    ProductContentOwnerResolver(ProductRepository productRepository, FindStoreOwnerUseCase storeOwner) {
        this.productRepository = productRepository;
        this.storeOwner = storeOwner;
    }

    @Override
    public ReportContentType type() {
        return ReportContentType.PUBLICACION;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> ownerAccountId(String contentId) {
        Long id = ProductIds.parse(contentId);
        if (id == null) {
            return Optional.empty();
        }
        return productRepository.findById(id)
                .flatMap(product -> storeOwner.execute(product.getStoreId()))
                .map(String::valueOf);
    }
}
