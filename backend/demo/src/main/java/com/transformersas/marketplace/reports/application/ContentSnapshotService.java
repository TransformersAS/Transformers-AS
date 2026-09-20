package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.port.ContentSnapshot;
import com.transformersas.marketplace.reports.domain.port.ContentSnapshotProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Busca el contenido reportado en el módulo que lo publica; si ese módulo aún no lo expone, no hay vista. */
@Service
public class ContentSnapshotService {

    private final List<ContentSnapshotProvider> providers;

    public ContentSnapshotService(List<ContentSnapshotProvider> providers) {
        this.providers = providers;
    }

    public Optional<ContentSnapshot> find(ReportContentType type, String contentId) {
        return providers.stream()
                .filter(provider -> provider.type() == type)
                .findFirst()
                .flatMap(provider -> provider.load(contentId));
    }
}
