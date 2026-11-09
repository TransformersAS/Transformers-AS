package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.ChangeStoreOperationalStatusUseCase;
import com.transformersas.marketplace.stores.application.usecase.GetStoreOperationalStatusUseCase;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionChange;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionStatus;
import com.transformersas.marketplace.suspensions.domain.repository.StoreSuspensionRepository;
import com.transformersas.marketplace.users.application.usecase.FindSellerAccountStanding;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Termina una suspensión vigente, sea porque el administrador la levanta (A4) o porque se cumplió su fin (A5), y decide
 * si la tienda vuelve a operar (pasos 18 a 20). Si la cuenta dueña tiene otra restricción que le impide vender (CU-22)
 * la tienda no se reactiva: queda RESTRICTED con ese motivo y se informa (A6).
 */
@Component
public class SuspensionClosing {

    static final String OTHER_RESTRICTION = "La cuenta del vendedor tiene una restricción vigente que le impide vender";

    /** Cómo terminó: reactivated es falso si otra restricción impidió reactivar la tienda. */
    public record Outcome(Long suspensionId, SuspensionStatus status, boolean reactivated) {
    }

    private final StoreSuspensionRepository suspensions;
    private final GetStoreOperationalStatusUseCase stores;
    private final ChangeStoreOperationalStatusUseCase storeStatus;
    private final FindSellerAccountStanding accounts;
    private final SuspensionAudit audit;
    private final SuspensionNotices notices;

    public SuspensionClosing(StoreSuspensionRepository suspensions, GetStoreOperationalStatusUseCase stores,
                             ChangeStoreOperationalStatusUseCase storeStatus, FindSellerAccountStanding accounts,
                             SuspensionAudit audit, SuspensionNotices notices) {
        this.suspensions = suspensions;
        this.stores = stores;
        this.storeStatus = storeStatus;
        this.accounts = accounts;
        this.audit = audit;
        this.notices = notices;
    }

    /**
     * Cierra la suspensión como LIFTED (con el administrador y su motivo) o EXPIRED (actorId nulo). Lanza CONFLICT
     * SUSPENSION_CONCURRENT_UPDATE si otro proceso la cambió o cerró antes; nada queda a medias.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome close(StoreSuspension suspension, SuspensionStatus status, Long actorId, String reason,
                         LocalDateTime now) {
        if (!suspensions.close(suspension.id(), suspension.version(), status, now, actorId, reason)) {
            throw BusinessException.conflict("SUSPENSION_CONCURRENT_UPDATE",
                    "La suspensión cambió mientras se procesaba; vuelve a consultarla");
        }
        StoreStatus result = storeStatus.endSuspension(suspension.storeId(), otherRestriction(suspension.storeId()));
        boolean reactivated = result == StoreStatus.ACTIVE;
        SuspensionChange.Type type = status == SuspensionStatus.LIFTED
                ? SuspensionChange.Type.LIFTED : SuspensionChange.Type.EXPIRED;
        suspensions.addChange(new SuspensionChange(null, suspension.id(), type, suspension.endsAt(), now, reason,
                actorId, !reactivated, now));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("suspensionId", suspension.id());
        details.put("reason", reason);
        details.put("storeStatus", result.name());
        audit.record(actorId, type == SuspensionChange.Type.LIFTED ? "STORE_SUSPENSION_LIFTED"
                : "STORE_SUSPENSION_EXPIRED", suspension.storeId(), details);
        if (!reactivated) {
            audit.record(actorId, "STORE_REACTIVATION_BLOCKED", suspension.storeId(),
                    Map.of("suspensionId", suspension.id(), "storeStatus", result.name()));
        }
        notices.suspensionEnded(suspension, reactivated, type == SuspensionChange.Type.LIFTED);
        return new Outcome(suspension.id(), status, reactivated);
    }

    /** Motivo de otra restricción que impide reactivar la tienda, o nulo si no la hay. */
    private String otherRestriction(Long storeId) {
        Long owner = stores.execute(storeId).map(store -> store.ownerAccountId()).orElse(null);
        if (owner == null) {
            return null;
        }
        boolean canSell = accounts.execute(owner).map(FindSellerAccountStanding.Standing::canSell).orElse(false);
        return canSell ? null : OTHER_RESTRICTION;
    }
}
