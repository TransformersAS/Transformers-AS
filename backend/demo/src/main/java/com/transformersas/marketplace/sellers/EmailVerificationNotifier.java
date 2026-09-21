package com.transformersas.marketplace.sellers;

/** Frontera de entrega del correo de verificación. Las implementaciones no deben guardar el token en claro. */
public interface EmailVerificationNotifier {

    void notifyVerification(String email, String token);
}
