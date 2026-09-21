package com.transformersas.marketplace.sellers;

/**
 * Condiciones para vender en el marketplace (CU-12). El texto se muestra al registrarse y lo que se guarda es la versión
 * aceptada: si algún día cambian las condiciones, se sube la versión y cada cuenta conserva la que aceptó.
 */
public final class SellerTerms {

    /** Versión vigente de las condiciones. */
    public static final String VERSION = "2026-09";

    public static final String TEXT = """
            1. Eres responsable de la información, el precio y el inventario de los productos que publiques.
            2. Debes despachar los pedidos a tiempo y cumplir la política de devoluciones de tu tienda (mínimo 30 días).
            3. Debes atender las reclamaciones de tus compradores; si no hay acuerdo, decide el equipo de soporte.
            4. No puedes publicar productos ilegales, falsificados ni que infrinjan derechos de terceros.
            5. El marketplace puede restringir o suspender tu tienda si incumples estas condiciones.""";

    /** Lo que el visitante lee antes de aceptar. */
    public record Terms(String version, String text) {
    }

    public static Terms current() {
        return new Terms(VERSION, TEXT);
    }

    private SellerTerms() {
    }
}
