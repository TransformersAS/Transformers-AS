package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountInformationRequest;
import com.transformersas.marketplace.restrictions.domain.port.AccountDirectory;
import com.transformersas.marketplace.restrictions.domain.repository.AccountInformationRequestRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * El administrador pide una aclaración a la persona antes de decidir (A3): se registra con un plazo de 72 horas, se
 * notifica y se audita. Una sola abierta por cuenta; si la anterior venció sin respuesta, se cierra como vencida y
 * se puede pedir otra (A4).
 */
@Service
public class RequestAccountInformationUseCase {

    private final AccountDirectory accounts;
    private final AccountInformationRequestRepository requests;
    private final RestrictionNotices notices;
    private final RestrictionAudit audit;

    public RequestAccountInformationUseCase(AccountDirectory accounts, AccountInformationRequestRepository requests,
                                             RestrictionNotices notices, RestrictionAudit audit) {
        this.accounts = accounts;
        this.requests = requests;
        this.notices = notices;
        this.audit = audit;
    }

    @Transactional
    public AccountInformationRequest execute(Long adminId, Long accountId, String message) {
        if (accounts.findById(accountId).isEmpty()) {
            throw BusinessException.notFound("ACCOUNT_NOT_FOUND", "La cuenta no existe");
        }
        LocalDateTime now = LocalDateTime.now();
        AccountInformationRequest request = AccountInformationRequest.ask(accountId, adminId, message, now);
        requests.findOpenByAccount(accountId).ifPresent(open -> {
            if (open.effectiveStatus(now) != AccountInformationRequest.Status.EXPIRED) {
                throw BusinessException.conflict("INFORMATION_ALREADY_OPEN",
                        "La cuenta ya tiene una solicitud de información pendiente de respuesta");
            }
            requests.expire(open.id());
        });
        Long id = requests.insert(request);
        notices.informationRequested(request, id);
        audit.record(adminId, "ACCOUNT_INFORMATION_REQUESTED", accountId,
                Map.of("requestId", id, "dueAt", request.dueAt().toString()));
        return requests.findById(id).orElseThrow();
    }
}
