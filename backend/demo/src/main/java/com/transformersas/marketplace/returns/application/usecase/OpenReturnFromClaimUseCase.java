package com.transformersas.marketplace.returns.application.usecase;

import com.transformersas.marketplace.returns.application.ReturnRecorder;
import com.transformersas.marketplace.returns.application.event.ClaimResolvedRequiringReturn;
import com.transformersas.marketplace.returns.domain.model.ReturnEvent;
import com.transformersas.marketplace.returns.domain.model.ReturnLine;
import com.transformersas.marketplace.returns.domain.model.ReturnOrigin;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.returns.domain.port.OrderForReturn;
import com.transformersas.marketplace.returns.domain.port.OrderForReturnReader;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Una reclamación (CU-13) exige devolver el producto: deja la devolución de esa línea Aprobada, sin plazo, con la
 * referencia a la reclamación y el reembolso acordado. Casos:
 * <ul>
 *   <li>la línea no tenía devolución: nace Aprobada (origen CLAIM);</li>
 *   <li>la línea la había rechazado el vendedor: se reabre Rechazada → Aprobada, y la reapertura y el cambio de origen
 *   quedan en la línea de tiempo (A6, D7);</li>
 *   <li>ya existe por esta misma reclamación: no cambia nada (idempotente);</li>
 *   <li>cualquier otro estado: 409 RETURN_ALREADY_EXISTS. La reapertura es solo desde Rechazada.</li>
 * </ul>
 * Va dentro de la transacción de quien lo llama (el oyente del evento, que corre en la de la reclamación): si algo falla, la
 * reclamación tampoco se guarda.
 */
@Service
public class OpenReturnFromClaimUseCase {
    public enum Outcome { CREATED, REOPENED, ALREADY_LINKED }

    public record Result(Outcome outcome, Long returnId) {
    }

    private final OrderForReturnReader orders;
    private final ReturnRequestRepository repository;
    private final ReturnRecorder recorder;
    private final Clock clock;

    public OpenReturnFromClaimUseCase(OrderForReturnReader orders, ReturnRequestRepository repository,
                                      ReturnRecorder recorder, Clock clock) {
        this.orders = orders;
        this.repository = repository;
        this.recorder = recorder;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Result execute(ClaimResolvedRequiringReturn claim) {
        OrderForReturn order = orders.findForBuyer(claim.orderId(), claim.buyerAccountId()).orElseThrow(
                () -> BusinessException.notFound("RETURN_ORDER_NOT_FOUND", "El pedido de la reclamación no existe"));
        LocalDateTime now = LocalDateTime.now(clock);
        boolean lineFound = false;
        for (ReturnLine line : order.lines()) {
            if (!line.productId().equals(claim.productId())) {
                continue;
            }
            lineFound = true;
            Optional<ReturnRequest> existing = repository.lockByOrderItemId(line.orderItemId());
            if (existing.isEmpty()) {
                return create(order, line, claim, now);
            }
            ReturnRequest request = existing.get();
            if (request.getOrigin() == ReturnOrigin.CLAIM && claim.claimId().equals(request.getOriginClaimId())) {
                return new Result(Outcome.ALREADY_LINKED, request.getId());
            }
            if (request.getStatus() == ReturnStatus.REJECTED) {
                return reopen(request, claim, now);
            }
        }
        if (!lineFound) {
            throw BusinessException.invalid("RETURN_LINE_NOT_IN_ORDER", "El producto no pertenece a ese pedido");
        }
        throw BusinessException.conflict("RETURN_ALREADY_EXISTS",
                "La línea ya tiene una devolución en curso o terminada; solo se reabre una rechazada");
    }

    private Result create(OrderForReturn order, ReturnLine line, ClaimResolvedRequiringReturn claim,
                          LocalDateTime now) {
        ReturnRequest request = ReturnRequest.approvedFromClaim(order.orderId(), line, order.buyerAccountId(),
                order.storeId(), claim.claimId(), claim.description(), claim.agreedRefund(), now);
        repository.insertIfAbsent(request);
        recorder.record(request, request.openingEvent(), now);
        notifyBuyer(request);
        return new Result(Outcome.CREATED, request.getId());
    }

    private Result reopen(ReturnRequest request, ClaimResolvedRequiringReturn claim, LocalDateTime now) {
        for (ReturnEvent event : request.reopenFromClaim(claim.claimId(), claim.agreedRefund(), now)) {
            recorder.record(request, event, now);
        }
        repository.save(request);
        notifyBuyer(request);
        return new Result(Outcome.REOPENED, request.getId());
    }

    private void notifyBuyer(ReturnRequest request) {
        recorder.notifyBuyer(request, "APPROVED_FROM_CLAIM", "APPROVED_FROM_CLAIM-" + request.getOriginClaimId(),
                "Tu reclamación requiere devolver el producto",
                "Para resolver tu reclamación, devuelve " + request.getLine().productName()
                        + ". Elige cómo enviarlo desde tus devoluciones.");
    }
}
