package com.transformersas.marketplace.returns.infrastructure.integration;

import com.transformersas.marketplace.returns.domain.port.StoreReturnPolicyReader;
import com.transformersas.marketplace.stores.application.usecase.FindStoreReturnWindowUseCase;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Lee el plazo de devolución de la tienda con el caso de uso público de tiendas (CU-18). */
@Component
class StoreReturnPolicyAdapter implements StoreReturnPolicyReader {
    private final FindStoreReturnWindowUseCase returnWindow;

    StoreReturnPolicyAdapter(FindStoreReturnWindowUseCase returnWindow) {
        this.returnWindow = returnWindow;
    }

    @Override
    public Optional<Integer> returnWindowDays(Long storeId) {
        return returnWindow.execute(storeId);
    }
}
