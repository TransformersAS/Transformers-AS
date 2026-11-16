package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionStatus;
import com.transformersas.marketplace.restrictions.domain.model.Texts;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** A6: el administrador retira una restricción antes de su fin, con un motivo. */
@Service
public class WithdrawRestrictionUseCase {

    private final AccountRestrictionRepository restrictions;
    private final RestrictionClosing closing;

    public WithdrawRestrictionUseCase(AccountRestrictionRepository restrictions, RestrictionClosing closing) {
        this.restrictions = restrictions;
        this.closing = closing;
    }

    @Transactional
    public RestrictionClosing.Outcome execute(Long adminId, Long restrictionId, String reason) {
        AccountRestriction restriction = restrictions.findById(restrictionId)
                .orElseThrow(() -> BusinessException.notFound("RESTRICTION_NOT_FOUND", "La restricción no existe"));
        restriction.requireActive();
        String text = Texts.required(reason, 10, 1000, "JUSTIFICATION_REQUIRED", "reason", "El motivo");
        return closing.close(restriction, RestrictionStatus.WITHDRAWN, adminId, text, LocalDateTime.now());
    }
}
