package com.transformersas.marketplace.product;

import com.transformersas.marketplace.reports.application.ContentVisibility;
import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.port.ReportableContentVerifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Una publicación se puede reportar si existe, está activa y la moderación no la ha ocultado ni retirado, es decir,
 * si el catálogo se la mostraría al reportante.
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
                && productRepository.findById(id).map(product -> Boolean.TRUE.equals(product.getActive()))
                .orElse(false);
    }
}
