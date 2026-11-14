package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.ChangeStoreOperationalStatusUseCase;
import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionChange;
import com.transformersas.marketplace.suspensions.domain.model.Texts;
import com.transformersas.marketplace.suspensions.domain.repository.StoreSuspensionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A3: el administrador cambia la duración total de una suspensión vigente, contada desde su inicio, con una
 * justificación. Se registra el cambio, se actualiza el motivo que ve la tienda y se notifica al vendedor.
 */
@Service
public class ChangeSuspensionDurationUseCase {

    private final StoreSuspensionRepository suspensions;
    private final ChangeStoreOperationalStatusUseCase storeStatus;
    private final SuspensionAudit audit;
    private final SuspensionNotices notices;

    public ChangeSuspensionDurationUseCase(StoreSuspensionRepository suspensions,
                                           ChangeStoreOperationalStatusUseCase storeStatus, SuspensionAudit audit,
                                           SuspensionNotices notices) {
        this.suspensions = suspensions;
        this.storeStatus = storeStatus;
        this.audit = audit;
        this.notices = notices;
    }

    @Transactional
    public StoreSuspension execute(Long adminId, Long suspensionId, Integer durationDays, String justification) {
        StoreSuspension suspension = suspensions.findById(suspensionId)
                .orElseThrow(() -> BusinessException.notFound("SUSPENSION_NOT_FOUND", "La suspensión no existe"));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newEnd = suspension.endWith(durationDays, now);
        String text = Texts.required(justification, 10, 1000, "JUSTIFICATION_REQUIRED", "justification",
                "La justificación");
        if (!suspensions.changeEnd(suspensionId, suspension.version(), newEnd)) {
            throw BusinessException.conflict("SUSPENSION_CONCURRENT_UPDATE",
                    "La suspensión cambió mientras se procesaba; vuelve a consultarla");
        }
        Long changeId = suspensions.addChange(new SuspensionChange(null, suspensionId,
                SuspensionChange.Type.DURATION_CHANGED, suspension.endsAt(), newEnd, text, adminId, false, now));
        StoreSuspension updated = suspensions.findById(suspensionId).orElseThrow();
        storeStatus.suspend(updated.storeId(), ApplySuspensionUseCase.statusReason(updated));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("suspensionId", suspensionId);
        details.put("previousEndsAt", suspension.endsAt().toString());
        details.put("newEndsAt", newEnd.toString());
        details.put("justification", text);
        audit.record(adminId, "STORE_SUSPENSION_DURATION_CHANGED", updated.storeId(), details);
        notices.durationChanged(updated, newEnd, changeId, text);
        return updated;
    }
}
