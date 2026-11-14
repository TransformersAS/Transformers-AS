package com.transformersas.marketplace.favorites;

import com.transformersas.marketplace.product.*;
import com.transformersas.marketplace.reports.application.ContentVisibility;
import com.transformersas.marketplace.reports.domain.model.*;
import org.springframework.stereotype.Component;

@Component
public class FollowedProductVisibility {
    private final ContentVisibility moderation;
    public FollowedProductVisibility(ContentVisibility moderation) { this.moderation=moderation; }
    public boolean readable(Product p) {
        return readable(p,moderation.nonVisibleIds(ReportContentType.PUBLICACION));
    }
    public java.util.Set<String> hiddenIds() { return moderation.nonVisibleIds(ReportContentType.PUBLICACION); }
    public boolean readable(Product p,java.util.Set<String> hidden) {
        return p.getStatus()!=ProductStatus.DRAFT && p.getStatus()!=ProductStatus.RETIRED
                && !hidden.contains(p.getId().toString());
    }
}
