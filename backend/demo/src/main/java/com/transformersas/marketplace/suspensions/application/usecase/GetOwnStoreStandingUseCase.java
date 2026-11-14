package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.dto.StoreOperationalStatus;
import com.transformersas.marketplace.stores.application.usecase.GetStoreOperationalStatusUseCase;
import com.transformersas.marketplace.suspensions.application.dto.SellerStanding;
import com.transformersas.marketplace.suspensions.application.dto.SuspensionView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Estado de la tienda del vendedor: si está suspendida, qué puede hacer y qué aclaraciones le piden. */
@Service
public class GetOwnStoreStandingUseCase {

    private final GetStoreOperationalStatusUseCase stores;
    private final SuspensionHistory history;

    public GetOwnStoreStandingUseCase(GetStoreOperationalStatusUseCase stores, SuspensionHistory history) {
        this.stores = stores;
        this.history = history;
    }

    @Transactional(readOnly = true)
    public SellerStanding execute(Long storeId) {
        StoreOperationalStatus store = stores.execute(storeId)
                .orElseThrow(() -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe"));
        List<SuspensionView> suspensions = history.suspensionsOf(storeId);
        return new SellerStanding(store.status(), store.statusReason(), SuspensionHistory.active(suspensions),
                suspensions, history.clarificationsOf(storeId, LocalDateTime.now()), SuspensionEffects.BLOCKED,
                SuspensionEffects.ALLOWED);
    }
}
