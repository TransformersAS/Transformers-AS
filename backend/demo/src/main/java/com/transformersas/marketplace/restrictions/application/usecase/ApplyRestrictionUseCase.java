package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionChange;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionScope;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectory;
import com.transformersas.marketplace.restrictions.domain.port.StoreOperationalLink;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;
import com.transformersas.marketplace.users.domain.model.Role;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aplica una restricción confirmada (pasos 13 a 18, RF-152 a RF-154). En una sola transacción: la restricción, su
 * historia, el efecto sobre la tienda de la cuenta cuando corresponde, la auditoría y la notificación interna. Si
 * algo falla no queda nada a medias (RNF-015); el aviso externo se pide después del commit.
 */
@Service
public class ApplyRestrictionUseCase {

    private final AccountDirectory accounts;
    private final AccountRestrictionRepository restrictions;
    private final StoreOperationalLink storeLink;
    private final RestrictionAudit audit;
    private final RestrictionNotices notices;

    public ApplyRestrictionUseCase(AccountDirectory accounts, AccountRestrictionRepository restrictions,
                                   StoreOperationalLink storeLink, RestrictionAudit audit,
                                   RestrictionNotices notices) {
        this.accounts = accounts;
        this.restrictions = restrictions;
        this.storeLink = storeLink;
        this.audit = audit;
        this.notices = notices;
    }

    @Transactional
    public AccountRestriction execute(Long adminId, Long accountId, RestrictionScope scope, Role role, String reason,
                                      boolean permanent, Integer durationDays) {
        if (accounts.findById(accountId).isEmpty()) {
            throw BusinessException.notFound("ACCOUNT_NOT_FOUND", "La cuenta no existe");
        }
        LocalDateTime now = LocalDateTime.now();
        AccountRestriction restriction = AccountRestriction.start(accountId, scope, role, reason, permanent,
                durationDays, adminId, now);
        Long id = restrictions.insert(restriction);
        restrictions.addChange(new RestrictionChange(null, id, RestrictionChange.Type.APPLIED, null,
                restriction.endsAt(), restriction.reason(), adminId, now));
        applyStoreEffect(accountId, scope, role, restriction.reason());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("restrictionId", id);
        details.put("scope", scope.name());
        details.put("role", role == null ? null : role.name());
        details.put("permanent", permanent);
        details.put("endsAt", restriction.endsAt() == null ? null : restriction.endsAt().toString());
        details.put("reason", restriction.reason());
        audit.record(adminId, "ACCOUNT_RESTRICTION_APPLIED", accountId, details);
        notices.restrictionApplied(restriction, id);
        return restrictions.findById(id).orElseThrow();
    }

    /** Si la restricción le impide vender (toda la cuenta o el rol VENDEDOR) y tiene tienda, la restringe también. */
    private void applyStoreEffect(Long accountId, RestrictionScope scope, Role role, String reason) {
        if (scope != RestrictionScope.ACCOUNT && role != Role.VENDEDOR) {
            return;
        }
        storeLink.storeOwnedBy(accountId).ifPresent(storeId -> storeLink.restrict(storeId, reason));
    }
}
