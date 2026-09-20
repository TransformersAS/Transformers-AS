package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.port.ContentOwnerResolver;
import com.transformersas.marketplace.reports.domain.port.ReportableContentVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Los tipos de contenido que se pueden reportar hoy: los que tienen un verificador registrado. Reseñas, respuestas y
 * mensajes no tienen módulo todavía y se rechazan con 422; cuando lo tengan, basta con que registren su verificador y
 * su resolutor de propietario.
 */
@Component
public class ReportableContentRegistry {
    private final Map<ReportContentType, ReportableContentVerifier> verifiers = new EnumMap<>(ReportContentType.class);
    private final Map<ReportContentType, ContentOwnerResolver> owners = new EnumMap<>(ReportContentType.class);

    public ReportableContentRegistry(List<ReportableContentVerifier> verifiers, List<ContentOwnerResolver> owners) {
        verifiers.forEach(verifier -> this.verifiers.put(verifier.type(), verifier));
        owners.forEach(owner -> this.owners.put(owner.type(), owner));
    }

    /** Verificador del tipo, o 422 CONTENT_TYPE_NOT_REPORTABLE si ese tipo aún no se puede reportar. */
    public ReportableContentVerifier verifier(ReportContentType type) {
        ReportableContentVerifier verifier = verifiers.get(type);
        if (verifier == null) {
            throw new ReportException(HttpStatus.UNPROCESSABLE_ENTITY, "CONTENT_TYPE_NOT_REPORTABLE",
                    "Este tipo de contenido todavía no se puede reportar");
        }
        return verifier;
    }

    public Optional<String> ownerOf(ReportContentType type, String contentId) {
        ContentOwnerResolver resolver = owners.get(type);
        return resolver == null ? Optional.empty() : resolver.ownerAccountId(contentId);
    }
}
