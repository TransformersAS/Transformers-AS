package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.suspensions.domain.model.ClarificationRequest;
import com.transformersas.marketplace.suspensions.domain.repository.ClarificationRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * El vendedor responde una aclaración de su tienda dentro del plazo (paso 8). La respuesta queda asociada a la
 * evaluación (RF-169). Una aclaración de otra tienda responde como inexistente.
 */
@Service
public class AnswerClarificationUseCase {

    private final ClarificationRepository clarifications;
    private final SuspensionAudit audit;

    public AnswerClarificationUseCase(ClarificationRepository clarifications, SuspensionAudit audit) {
        this.clarifications = clarifications;
        this.audit = audit;
    }

    @Transactional
    public ClarificationRequest execute(Long sellerAccountId, Long storeId, Long clarificationId, String response) {
        ClarificationRequest request = clarifications.findById(clarificationId)
                .filter(found -> found.storeId().equals(storeId))
                .orElseThrow(() -> BusinessException.notFound("CLARIFICATION_NOT_FOUND",
                        "La aclaración no existe"));
        LocalDateTime now = LocalDateTime.now();
        String text = request.acceptResponse(response, now);
        if (!clarifications.answer(clarificationId, text, sellerAccountId, now)) {
            throw BusinessException.conflict("CLARIFICATION_ALREADY_ANSWERED", "Esta aclaración ya tiene respuesta");
        }
        audit.record(sellerAccountId, "STORE_CLARIFICATION_ANSWERED", storeId,
                Map.of("clarificationId", clarificationId));
        return clarifications.findById(clarificationId).orElseThrow();
    }
}
