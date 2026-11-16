package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountInformationRequest;
import com.transformersas.marketplace.restrictions.domain.repository.AccountInformationRequestRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/** La persona responde una solicitud sobre su propia cuenta dentro del plazo (A4). */
@Service
public class AnswerAccountInformationUseCase {

    private final AccountInformationRequestRepository requests;
    private final RestrictionAudit audit;

    public AnswerAccountInformationUseCase(AccountInformationRequestRepository requests, RestrictionAudit audit) {
        this.requests = requests;
        this.audit = audit;
    }

    @Transactional
    public AccountInformationRequest execute(Long accountId, Long requestId, String response) {
        AccountInformationRequest request = requests.findById(requestId)
                .filter(found -> found.accountId().equals(accountId))
                .orElseThrow(() -> BusinessException.notFound("INFORMATION_NOT_FOUND",
                        "La solicitud no existe"));
        LocalDateTime now = LocalDateTime.now();
        String text = request.acceptResponse(response, now);
        if (!requests.answer(requestId, text, now)) {
            throw BusinessException.conflict("INFORMATION_ALREADY_ANSWERED", "Esta solicitud ya tiene respuesta");
        }
        audit.record(accountId, "ACCOUNT_INFORMATION_ANSWERED", accountId, Map.of("requestId", requestId));
        return requests.findById(requestId).orElseThrow();
    }
}
