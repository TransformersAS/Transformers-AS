package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.GetStoreOperationalStatusUseCase;
import com.transformersas.marketplace.suspensions.domain.model.ClarificationRequest;
import com.transformersas.marketplace.suspensions.domain.repository.ClarificationRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * El administrador pide una aclaración al vendedor (pasos 6 y 7): se registra con un plazo de 72 horas, se notifica al
 * vendedor y se audita. Una sola abierta por tienda; si la anterior venció sin respuesta, se cierra como vencida y se
 * puede pedir otra (A2).
 */
@Service
public class RequestClarificationUseCase {

    private final GetStoreOperationalStatusUseCase stores;
    private final ClarificationRepository clarifications;
    private final SuspensionNotices notices;
    private final SuspensionAudit audit;

    public RequestClarificationUseCase(GetStoreOperationalStatusUseCase stores, ClarificationRepository clarifications,
                                       SuspensionNotices notices, SuspensionAudit audit) {
        this.stores = stores;
        this.clarifications = clarifications;
        this.notices = notices;
        this.audit = audit;
    }

    @Transactional
    public ClarificationRequest execute(Long adminId, Long storeId, String message) {
        if (stores.execute(storeId).isEmpty()) {
            throw BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe");
        }
        LocalDateTime now = LocalDateTime.now();
        ClarificationRequest request = ClarificationRequest.ask(storeId, adminId, message, now);
        clarifications.findOpenByStore(storeId).ifPresent(open -> {
            if (open.effectiveStatus(now) != ClarificationRequest.Status.EXPIRED) {
                throw BusinessException.conflict("CLARIFICATION_ALREADY_OPEN",
                        "La tienda ya tiene una aclaración pendiente de respuesta");
            }
            clarifications.expire(open.id());
        });
        Long id = clarifications.insert(request);
        notices.clarificationRequested(request, id);
        audit.record(adminId, "STORE_CLARIFICATION_REQUESTED", storeId,
                Map.of("clarificationId", id, "dueAt", request.dueAt().toString()));
        return clarifications.findById(id).orElseThrow();
    }
}
