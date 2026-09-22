package com.transformersas.marketplace.returns.application.usecase;

import com.transformersas.marketplace.returns.application.ReturnRecorder;
import com.transformersas.marketplace.returns.domain.model.ReturnEvent;
import com.transformersas.marketplace.returns.domain.model.ReturnPolicy;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Logística confirmó que la mercancía llegó al vendedor (CU-25): la devolución pasa a En inspección y el vendedor tiene 24 h
 * para reportar un problema. La ventana cuenta desde que la devolución lo registra, no desde la hora del proveedor, para
 * que un evento que llega tarde no le quite tiempo al vendedor.
 *
 * <p>Corre dentro de la transacción de logística (el evento se publica en ella): lo que hace se confirma o se revierte con
 * el seguimiento. Por eso no lanza por situaciones esperadas: un evento repetido, o de una devolución que no está
 * Aprobada o no existe, no cambia nada y se registra en el log (A10).
 */
@Service
public class StartInspectionUseCase {
    private static final Logger log = LoggerFactory.getLogger(StartInspectionUseCase.class);

    private final ReturnRequestRepository repository;
    private final ReturnRecorder recorder;
    private final ReturnPolicy policy;
    private final Clock clock;

    public StartInspectionUseCase(ReturnRequestRepository repository, ReturnRecorder recorder, ReturnPolicy policy,
                                  Clock clock) {
        this.repository = repository;
        this.recorder = recorder;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void execute(Long returnId) {
        Optional<ReturnRequest> found = repository.lockById(returnId);
        if (found.isEmpty()) {
            log.warn("Entrega al vendedor de una devolución desconocida returnId={}", returnId);
            return;
        }
        ReturnRequest request = found.get();
        if (request.getStatus() != ReturnStatus.APPROVED) {
            log.info("Entrega al vendedor ignorada returnId={} estado={}", returnId, request.getStatus());
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ReturnEvent event = request.startInspection(now, policy.inspectionWindow()).orElseThrow();
        repository.save(request);
        recorder.record(request, event, now);
        recorder.notifyStore(request, "INSPECTION_STARTED", "INSPECTION_STARTED", "Devolución recibida",
                "Recibiste la devolución #" + request.getId() + " de " + request.getLine().productName()
                        + ". Tienes 24 horas para reportar un problema.");
        recorder.notifyBuyer(request, "INSPECTION_STARTED", "INSPECTION_STARTED", "Tu devolución llegó al vendedor",
                "El vendedor tiene 24 horas para revisarla; luego iniciamos tu reembolso.");
    }
}
