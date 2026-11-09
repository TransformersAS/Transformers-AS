package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionStatus;
import com.transformersas.marketplace.suspensions.domain.model.Texts;
import com.transformersas.marketplace.suspensions.domain.repository.StoreSuspensionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** A4: el administrador levanta una suspensión antes de su fin, con un motivo. La tienda se reactiva si puede. */
@Service
public class LiftSuspensionUseCase {

    private final StoreSuspensionRepository suspensions;
    private final SuspensionClosing closing;

    public LiftSuspensionUseCase(StoreSuspensionRepository suspensions, SuspensionClosing closing) {
        this.suspensions = suspensions;
        this.closing = closing;
    }

    @Transactional
    public SuspensionClosing.Outcome execute(Long adminId, Long suspensionId, String reason) {
        StoreSuspension suspension = suspensions.findById(suspensionId)
                .orElseThrow(() -> BusinessException.notFound("SUSPENSION_NOT_FOUND", "La suspensión no existe"));
        suspension.requireActive();
        String text = Texts.required(reason, 10, 1000, "JUSTIFICATION_REQUIRED", "reason", "El motivo");
        return closing.close(suspension, SuspensionStatus.LIFTED, adminId, text, LocalDateTime.now());
    }
}
