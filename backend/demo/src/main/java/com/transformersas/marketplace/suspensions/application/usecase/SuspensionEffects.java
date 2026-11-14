package com.transformersas.marketplace.suspensions.application.usecase;

import java.util.List;

/**
 * Efectos de una suspensión (RF-171), como los ven el administrador antes de confirmarla (paso 12) y el vendedor
 * mientras dura. Los bloqueos los aplican productos (CheckStoreCommercialOperationUseCase.requireForSeller) y checkout
 * (requireForPurchase); lo permitido no pasa por ninguna comprobación de suspensión. Promociones y cupones se listan
 * porque los exige la especificación, pero hoy ningún vendedor puede crearlos (el único cupón, DESC10, es global).
 */
public final class SuspensionEffects {

    public static final List<String> BLOCKED = List.of(
            "Publicar productos nuevos",
            "Reactivar publicaciones pausadas",
            "Modificar la información comercial de sus publicaciones",
            "Crear promociones o cupones",
            "Recibir nuevas compras: sus productos dejan de mostrarse en el catálogo");

    public static final List<String> ALLOWED = List.of(
            "Atender los pedidos existentes hasta su entrega",
            "Atender reclamaciones y devoluciones",
            "Responder mensajes de operaciones anteriores",
            "Consultar el historial y la analítica de su tienda",
            "Pausar o retirar publicaciones",
            "Usar los demás roles de su cuenta, por ejemplo comprar");

    private SuspensionEffects() {
    }
}
