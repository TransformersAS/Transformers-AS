package com.transformersas.marketplace.returns.infrastructure.integration;

import com.transformersas.marketplace.logistics.application.dto.RegisterReturnShipmentCommand;
import com.transformersas.marketplace.logistics.application.usecase.RegisterReturnShipmentUseCase;
import com.transformersas.marketplace.returns.domain.port.ReturnShipmentRegistrar;
import org.springframework.stereotype.Component;

/** Registra el seguimiento con el caso de uso de CU-25, que ya es idempotente y se une a la transacción en curso. */
@Component
class LogisticsShipmentRegistrar implements ReturnShipmentRegistrar {
    private final RegisterReturnShipmentUseCase register;

    LogisticsShipmentRegistrar(RegisterReturnShipmentUseCase register) {
        this.register = register;
    }

    @Override
    public void register(Long returnId, Long buyerAccountId, Long storeId, String providerReturnId,
                         String trackingCode) {
        register.execute(new RegisterReturnShipmentCommand(returnId, buyerAccountId, storeId, providerReturnId,
                trackingCode));
    }
}
