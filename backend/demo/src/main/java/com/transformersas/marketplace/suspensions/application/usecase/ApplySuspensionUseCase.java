package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.ChangeStoreOperationalStatusUseCase;
import com.transformersas.marketplace.stores.application.usecase.GetStoreOperationalStatusUseCase;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.suspensions.domain.model.StoreReview;
import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionChange;
import com.transformersas.marketplace.suspensions.domain.repository.StoreReviewRepository;
import com.transformersas.marketplace.suspensions.domain.repository.StoreSuspensionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aplica una suspensión temporal confirmada (pasos 13 a 17, RF-170). En una sola transacción: la suspensión, la
 * revisión que la decidió, su historia, el estado SUSPENDED de la tienda, la auditoría y la notificación interna. Si
 * algo falla no queda nada a medias (RNF-015); el aviso externo se pide después del commit.
 */
@Service
public class ApplySuspensionUseCase {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final GetStoreOperationalStatusUseCase stores;
    private final ChangeStoreOperationalStatusUseCase storeStatus;
    private final StoreSuspensionRepository suspensions;
    private final StoreReviewRepository reviews;
    private final SuspensionAudit audit;
    private final SuspensionNotices notices;

    public ApplySuspensionUseCase(GetStoreOperationalStatusUseCase stores,
                                  ChangeStoreOperationalStatusUseCase storeStatus,
                                  StoreSuspensionRepository suspensions, StoreReviewRepository reviews,
                                  SuspensionAudit audit, SuspensionNotices notices) {
        this.stores = stores;
        this.storeStatus = storeStatus;
        this.suspensions = suspensions;
        this.reviews = reviews;
        this.audit = audit;
        this.notices = notices;
    }

    @Transactional
    public StoreSuspension execute(Long adminId, Long storeId, String reason, Integer durationDays) {
        if (stores.execute(storeId).isEmpty()) {
            throw BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe");
        }
        LocalDateTime now = LocalDateTime.now();
        StoreSuspension suspension = StoreSuspension.start(storeId, reason, durationDays, adminId, now);
        Long id = suspensions.insert(suspension);
        reviews.insert(new StoreReview(null, storeId, adminId, StoreReview.Decision.SUSPENDED, suspension.reason(), id,
                now));
        suspensions.addChange(new SuspensionChange(null, id, SuspensionChange.Type.APPLIED, null,
                suspension.endsAt(), suspension.reason(), adminId, false, now));
        StoreStatus result = storeStatus.suspend(storeId, statusReason(suspension));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("suspensionId", id);
        details.put("startsAt", suspension.startsAt().toString());
        details.put("endsAt", suspension.endsAt().toString());
        details.put("durationDays", durationDays);
        details.put("reason", suspension.reason());
        details.put("storeStatus", result.name());
        audit.record(adminId, "STORE_SUSPENSION_APPLIED", storeId, details);
        notices.suspensionApplied(suspension, id);
        return suspensions.findById(id).orElseThrow();
    }

    /** Motivo que la tienda muestra mientras está suspendida; lo ve el vendedor al intentar publicar (A9). */
    static String statusReason(StoreSuspension suspension) {
        return "Suspensión temporal hasta el " + DATE.format(suspension.endsAt()) + ". Motivo: " + suspension.reason();
    }
}
