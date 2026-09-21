package com.transformersas.marketplace.returns.infrastructure.integration;

import com.transformersas.marketplace.logistics.application.dto.ReturnDeliveredToSeller;
import com.transformersas.marketplace.returns.application.event.ClaimResolvedRequiringReturn;
import com.transformersas.marketplace.returns.application.usecase.OpenReturnFromClaimUseCase;
import com.transformersas.marketplace.returns.application.usecase.StartInspectionUseCase;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Oyentes de eventos de otros módulos. Son síncronos y corren en la transacción de quien publica el evento: lo que
 * hacen se confirma o se revierte con ella.
 */
@Component
class ReturnEventListeners {
    private final OpenReturnFromClaimUseCase openFromClaim;
    private final StartInspectionUseCase startInspection;

    ReturnEventListeners(OpenReturnFromClaimUseCase openFromClaim, StartInspectionUseCase startInspection) {
        this.openFromClaim = openFromClaim;
        this.startInspection = startInspection;
    }

    /** Una reclamación exige devolver el producto (CU-13). */
    @EventListener
    void onClaimResolvedRequiringReturn(ClaimResolvedRequiringReturn event) {
        openFromClaim.execute(event);
    }

    /** Logística confirmó la entrega al vendedor (CU-25): empieza la inspección de 24 h. */
    @EventListener
    void onReturnDeliveredToSeller(ReturnDeliveredToSeller event) {
        startInspection.execute(event.returnId());
    }
}
