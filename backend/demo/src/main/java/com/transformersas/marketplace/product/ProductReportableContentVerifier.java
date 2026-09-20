package com.transformersas.marketplace.product;

import com.transformersas.marketplace.reports.application.ContentVisibility;
import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.port.ReportableContentVerifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Una publicación se puede reportar si existe, está activa, no es un borrador ni está retirada por su vendedor (CU-14) y
 * la moderación no la ha ocultado ni retirado, es decir, si el catálogo se la mostraría al reportante. Un borrador
 * responde igual que un producto inexistente, para no revelar que existe.
 */
@Component
class ProductReportableContentVerifier implements ReportableContentVerifier {
    private final ProductRepository productRepository;
    private final ContentVisibility contentVisibility;

    ProductReportableContentVerifier(ProductRepository productRepository, ContentVisibility contentVisibility) {
        this.productRepository = productRepository;
        this.contentVisibility = contentVisibility;
    }

    @Override
    public ReportContentType type() {
        return ReportContentType.PUBLICACION;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAccessibleTo(String contentId, String reporterId) {
        Long id = ProductIds.parse(contentId);
        return id != null
                && contentVisibility.stateOf(ReportContentType.PUBLICACION, contentId) == ContentModerationState.VISIBLE
                && productRepository.findById(id).map(ProductReportableContentVerifier::isPublic)
                .orElse(false);
    }

    private static boolean isPublic(Product product) {
        return Boolean.TRUE.equals(product.getActive()) && product.getStatus() != ProductStatus.DRAFT
                && product.getStatus() != ProductStatus.RETIRED;
    }
}
