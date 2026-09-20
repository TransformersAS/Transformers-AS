package com.transformersas.marketplace.reports.infrastructure.web.controller;

import com.transformersas.marketplace.reports.domain.port.ReportEvidenceStorage.EvidenceContent;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

/**
 * Sirve una imagen de evidencia con ETag (el sha256 del contenido): quien ya la tiene recibe 304 sin descargarla de
 * nuevo. La evidencia es privada, así que la caché es solo del navegador y siempre se revalida.
 */
final class EvidenceResponses {
    private EvidenceResponses() {
    }

    private static final CacheControl PRIVATE = CacheControl.noCache().cachePrivate();

    static ResponseEntity<byte[]> serve(WebRequest request, EvidenceContent evidence) {
        String etag = "\"" + evidence.summary().sha256() + "\"";
        if (request.checkNotModified(etag)) {
            // El 304 repite ETag y Cache-Control: sin ellos una caché intermedia podría tratar la imagen como pública.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(PRIVATE).build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(evidence.summary().contentType()))
                .eTag(etag).cacheControl(PRIVATE)
                .header("X-Content-Type-Options", "nosniff").body(evidence.data());
    }
}
