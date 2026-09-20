package com.transformersas.marketplace.reports.domain.port;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;

import java.util.Optional;

/** Cuenta propietaria de un contenido de un tipo dado; sirve para impedir que alguien reporte lo suyo (CU-20). */
public interface ContentOwnerResolver {

    ReportContentType type();

    /** Id de la cuenta propietaria; vacío si el contenido no existe o no tiene propietario conocido. */
    Optional<String> ownerAccountId(String contentId);
}
