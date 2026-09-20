package com.transformersas.marketplace.stores.infrastructure.web.response;

import com.transformersas.marketplace.stores.application.dto.StoreSettingsView;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreImageSummary;

import java.util.List;

/**
 * Configuración de la tienda para su dueña. status y statusReason informan por qué no puede modificarse (A8) y
 * version es la que debe devolver al guardar (A10). minReturnWindowDays es el plazo de devolución mínimo que exige
 * el marketplace (A5), para que el cliente lo muestre sin fijarlo por su cuenta.
 */
public record StoreSettingsResponse(
        Long id,
        String name,
        String description,
        String contactEmail,
        String contactPhone,
        String businessHours,
        int returnWindowDays,
        int minReturnWindowDays,
        String policyText,
        String status,
        String statusReason,
        boolean canModify,
        long version,
        ShippingMethods shippingMethods,
        List<ImageInfo> images
) {

    public record ShippingMethods(List<String> enabled, List<String> available) {
    }

    /** Metadatos de una imagen; el contenido se pide a url, cuya versión (sha256) cambia con la imagen. */
    public record ImageInfo(String kind, String contentType, long sizeBytes, String sha256, String url) {
    }

    public static StoreSettingsResponse from(StoreSettingsView view) {
        Store store = view.store();
        return new StoreSettingsResponse(store.id(), store.profile().name(), store.profile().description(),
                store.profile().contactEmail(), store.profile().contactPhone(), store.profile().businessHours(),
                store.policy().returnWindowDays(), view.minReturnWindowDays(), store.policy().text(),
                store.status().name(), store.statusReason(),
                view.canModify(), store.version(),
                new ShippingMethods(view.enabledShippingMethods(), view.availableShippingMethods()),
                view.images().stream().map(image -> imageInfo(store.id(), image)).toList());
    }

    public static ImageInfo imageInfo(Long storeId, StoreImageSummary image) {
        return new ImageInfo(image.kind().name(), image.contentType(), image.sizeBytes(), image.sha256(),
                "/api/stores/" + storeId + "/images/" + image.kind().path() + "?v=" + image.sha256());
    }
}
