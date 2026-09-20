package com.transformersas.marketplace.logistics.application.dto;

/**
 * Datos con los que CU-19 entrega a logística una devolución aprobada cuyo retorno ya tiene referencia logística.
 * buyerAccountId y storeId salen de la devolución de CU-19 (el destino lo define la tienda, no el comprador).
 */
public record RegisterReturnShipmentCommand(
        Long returnId,
        Long buyerAccountId,
        Long storeId,
        String providerReturnId,
        String trackingCode
) {
    public RegisterReturnShipmentCommand {
        requirePositive(returnId, "returnId");
        requirePositive(buyerAccountId, "buyerAccountId");
        requirePositive(storeId, "storeId");
        requireText(providerReturnId, "providerReturnId");
        requireText(trackingCode, "trackingCode");
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " debe ser un identificador positivo");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(field + " es obligatorio (hasta 100 caracteres)");
        }
    }
}
