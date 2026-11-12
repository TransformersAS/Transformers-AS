package com.transformersas.marketplace.users.application.usecase;

/** Versioned academic marketplace notices, distinct from seller conditions. */
public final class BuyerTerms {
    private BuyerTerms() { }
    public static final String VERSION = "2026-11";
    public record Notices(String termsVersion, String terms, String privacyVersion, String privacy) { }
    public static Notices current() {
        return new Notices(VERSION,
                "Usa tu cuenta para comprar en el marketplace y proporciona datos correctos. Conserva tus credenciales de forma segura.",
                VERSION, "El marketplace almacena tu nombre, fecha de nacimiento, correo y teléfono para gestionar tu cuenta y tus compras. Tu contraseña se almacena únicamente como hash.");
    }
}
