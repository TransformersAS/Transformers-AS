package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionChange;
import com.transformersas.marketplace.restrictions.domain.model.Texts;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A7: el administrador cambia la duración o la permanencia de una restricción vigente, siempre con una
 * justificación. El alcance (cuenta o rol) y el rol afectado no se modifican aquí: para eso se retira la restricción
 * (A6) y se aplica una nueva con el alcance correcto.
 */
@Service
public class ModifyRestrictionUseCase {

    private final AccountRestrictionRepository restrictions;
    private final RestrictionAudit audit;
    private final RestrictionNotices notices;

    public ModifyRestrictionUseCase(AccountRestrictionRepository restrictions, RestrictionAudit audit,
                                    RestrictionNotices notices) {
        this.restrictions = restrictions;
        this.audit = audit;
        this.notices = notices;
    }

    /** Cambia la duración total de una restricción temporal, contada desde su inicio. */
    @Transactional
    public AccountRestriction changeDuration(Long adminId, Long restrictionId, Integer durationDays,
                                             String justification) {
        AccountRestriction restriction = find(restrictionId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newEnd = restriction.endWith(durationDays, now);
        String text = requireJustification(justification);
        if (!restrictions.changeEnd(restrictionId, restriction.version(), newEnd)) {
            throw concurrentUpdate();
        }
        Long changeId = restrictions.addChange(new RestrictionChange(null, restrictionId,
                RestrictionChange.Type.DURATION_CHANGED, restriction.endsAt(), newEnd, text, adminId, now));
        AccountRestriction updated = find(restrictionId);
        audit(adminId, "ACCOUNT_RESTRICTION_DURATION_CHANGED", updated, Map.of("newEndsAt", newEnd.toString(),
                "justification", text));
        notices.durationChanged(updated, changeId, text);
        return updated;
    }

    /** Convierte una restricción temporal vigente en permanente. */
    @Transactional
    public AccountRestriction makePermanent(Long adminId, Long restrictionId, String justification) {
        AccountRestriction restriction = find(restrictionId);
        restriction.requireActive();
        if (restriction.permanent()) {
            throw BusinessException.conflict("RESTRICTION_ALREADY_PERMANENT", "La restricción ya es permanente");
        }
        String text = requireJustification(justification);
        if (!restrictions.makePermanent(restrictionId, restriction.version())) {
            throw concurrentUpdate();
        }
        restrictions.addChange(new RestrictionChange(null, restrictionId, RestrictionChange.Type.MADE_PERMANENT,
                restriction.endsAt(), null, text, adminId, LocalDateTime.now()));
        AccountRestriction updated = find(restrictionId);
        audit(adminId, "ACCOUNT_RESTRICTION_MADE_PERMANENT", updated, Map.of("justification", text));
        notices.restrictionApplied(updated, updated.id());
        return updated;
    }

    /** Convierte una restricción permanente vigente en temporal, con la duración indicada desde ahora. */
    @Transactional
    public AccountRestriction makeTemporary(Long adminId, Long restrictionId, Integer durationDays,
                                            String justification) {
        AccountRestriction restriction = find(restrictionId);
        restriction.requireActive();
        if (!restriction.permanent()) {
            throw BusinessException.conflict("RESTRICTION_ALREADY_TEMPORARY", "La restricción ya es temporal");
        }
        String text = requireJustification(justification);
        LocalDateTime now = LocalDateTime.now();
        AccountRestriction asTemporary = AccountRestriction.start(restriction.accountId(), restriction.scope(),
                restriction.role(), restriction.reason(), false, durationDays, restriction.appliedBy(), now);
        if (!restrictions.makeTemporary(restrictionId, restriction.version(), asTemporary.endsAt())) {
            throw concurrentUpdate();
        }
        restrictions.addChange(new RestrictionChange(null, restrictionId, RestrictionChange.Type.MADE_TEMPORARY,
                null, asTemporary.endsAt(), text, adminId, now));
        AccountRestriction updated = find(restrictionId);
        audit(adminId, "ACCOUNT_RESTRICTION_MADE_TEMPORARY", updated, Map.of("endsAt",
                updated.endsAt().toString(), "justification", text));
        notices.durationChanged(updated, updated.id(), text);
        return updated;
    }

    private AccountRestriction find(Long restrictionId) {
        return restrictions.findById(restrictionId)
                .orElseThrow(() -> BusinessException.notFound("RESTRICTION_NOT_FOUND", "La restricción no existe"));
    }

    private static String requireJustification(String justification) {
        return Texts.required(justification, 10, 1000, "JUSTIFICATION_REQUIRED", "justification",
                "La justificación");
    }

    private static BusinessException concurrentUpdate() {
        return BusinessException.conflict("RESTRICTION_CONCURRENT_UPDATE",
                "La restricción cambió mientras se procesaba; vuelve a consultarla");
    }

    private void audit(Long adminId, String action, AccountRestriction restriction, Map<String, ?> extra) {
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("restrictionId", restriction.id());
        this.audit.record(adminId, action, restriction.accountId(), details);
    }
}
