package com.transformersas.marketplace.stores.infrastructure.web.controller;

import com.transformersas.marketplace.stores.application.usecase.GetStoreImageUseCase;
import com.transformersas.marketplace.stores.domain.model.StoreImage;
import com.transformersas.marketplace.stores.domain.model.StoreImageKind;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Sirve el logo y la portada de una tienda, que son públicos (RF-059). Lleva ETag (el sha256 del contenido) y
 * Cache-Control con revalidación: un cliente que ya la tiene recibe 304 sin volver a descargarla (RNF-047).
 */
@RestController
@RequestMapping("/api/stores/{storeId}/images")
public class StoreImageController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).mustRevalidate();

    private final GetStoreImageUseCase getImage;

    public StoreImageController(GetStoreImageUseCase getImage) {
        this.getImage = getImage;
    }

    @GetMapping("/{kind}")
    public ResponseEntity<byte[]> image(@PathVariable Long storeId, @PathVariable String kind) {
        StoreImage image = getImage.execute(storeId, StoreImageKind.fromPath(kind));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .eTag(image.sha256()).cacheControl(CACHE).body(image.data());
    }
}
