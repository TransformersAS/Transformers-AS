package com.transformersas.marketplace.reports.domain.port;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;

/**
 * Comprueba, para un tipo de contenido, que se puede reportar (CU-20): que existe y que quien reporta tiene
 * acceso a él. Cada módulo que quiera ser reportable registra un verificador; un tipo sin verificador se rechaza.
 */
public interface ReportableContentVerifier {

    ReportContentType type();

    /**
     * Verdadero si el contenido existe y el reportante puede verlo. No distingue "no existe" de "sin acceso" para
     * que la respuesta no revele datos privados.
     */
    boolean isAccessibleTo(String contentId, String reporterId);
}
