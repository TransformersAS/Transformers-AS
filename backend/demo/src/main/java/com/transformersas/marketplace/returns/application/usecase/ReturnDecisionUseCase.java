package com.transformersas.marketplace.returns.application.usecase;

import com.transformersas.marketplace.returns.application.ReturnRecorder;
import com.transformersas.marketplace.returns.domain.model.ReturnEvent;
import com.transformersas.marketplace.returns.domain.model.ReturnPolicy;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.port.ClaimOpener;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Lo que la tienda hace con una solicitud (RF-106 a RF-108) y lo que el comprador responde (RF-052): abrir la revisión,
 * pedir información con 24 h, aprobar o rechazar con justificación, y responder. Cada acción bloquea la devolución, aplica
 * la transición del agregado y guarda todo (cambio, línea de tiempo, auditoría y aviso) en una sola transacción: un
 * error o un conflicto no deja nada a medias (A12) y dos acciones simultáneas sobre la misma devolución se serializan.
 * Una devolución de otra tienda o de otro comprador responde 404.
 */
@Service
@Transactional
public class ReturnDecisionUseCase {
    private final ReturnRequestRepository repository;
    private final ReturnRecorder recorder;
    private final ClaimOpener claims;
    private final ReturnPolicy policy;
    private final Clock clock;

    public ReturnDecisionUseCase(ReturnRequestRepository repository, ReturnRecorder recorder, ClaimOpener claims,
                                 ReturnPolicy policy, Clock clock) {
        this.repository = repository;
        this.recorder = recorder;
        this.claims = claims;
        this.policy = policy;
        this.clock = clock;
    }

    public void startReview(Long storeId, Long sellerAccountId, Long returnId) {
        ReturnRequest request = ofStore(storeId, returnId);
        LocalDateTime now = now();
        ReturnEvent event = request.startReview(sellerAccountId, now);
        repository.save(request);
        recorder.record(request, event, now);
    }

    public void requestInformation(Long storeId, Long sellerAccountId, Long returnId, String message) {
        ReturnRequest request = ofStore(storeId, returnId);
        LocalDateTime now = now();
        ReturnRequest.InformationAsked asked = request.requestInformation(sellerAccountId, message, now,
                policy.informationWindow());
        Long infoId = repository.addInformationRequest(returnId, asked.request());
        repository.save(request);
        recorder.record(request, asked.event(), now);
        recorder.notifyBuyer(request, "INFORMATION_REQUESTED", "INFORMATION_REQUESTED-" + infoId,
                "Necesitamos más información de tu devolución",
                "El vendedor pidió información sobre tu devolución de " + request.getLine().productName()
                        + ". Tienes 24 horas para responder.");
    }

    public void approve(Long storeId, Long sellerAccountId, Long returnId, String note) {
        ReturnRequest request = ofStore(storeId, returnId);
        LocalDateTime now = now();
        ReturnEvent event = request.approve(sellerAccountId, note, now);
        repository.save(request);
        recorder.record(request, event, now);
        recorder.notifyBuyer(request, "APPROVED", "APPROVED", "Tu devolución fue aprobada",
                "El vendedor aprobó la devolución de " + request.getLine().productName()
                        + ". Elige cómo enviar el producto.");
    }

    public void reject(Long storeId, Long sellerAccountId, Long returnId, String note) {
        ReturnRequest request = ofStore(storeId, returnId);
        LocalDateTime now = now();
        ReturnEvent event = request.reject(sellerAccountId, note, now);
        repository.save(request);
        recorder.record(request, event, now);
        recorder.notifyBuyer(request, "REJECTED", "REJECTED", "Tu devolución fue rechazada",
                "El vendedor rechazó la devolución de " + request.getLine().productName()
                        + ". Puedes abrir una reclamación si no estás de acuerdo.");
    }

    /**
     * La tienda reporta un problema con lo recibido dentro de la ventana de inspección (A8, RF-053): abre una reclamación a
     * nombre del comprador con ese problema como descripción y deja la devolución con la marca de problema, sin cambiar de
     * estado ni reembolsar. La reclamación y la marca se guardan juntas: si una falla, ninguna queda.
     */
    public void reportProblem(Long storeId, Long sellerAccountId, Long returnId, String description) {
        ReturnRequest request = ofStore(storeId, returnId);
        LocalDateTime now = now();
        request.ensureCanReportProblem(now);
        Long claimId = claims.open(request.getBuyerAccountId(), request.getOrderId(), request.getLine().productId(),
                description == null ? "" : description.strip());
        ReturnEvent event = request.reportProblem(sellerAccountId, description, claimId, now);
        repository.save(request);
        recorder.record(request, event, now);
        recorder.notifyBuyer(request, "PROBLEM_REPORTED", "PROBLEM_REPORTED", "El vendedor reportó un problema",
                "Abrimos por ti la reclamación #" + claimId + " sobre la devolución de "
                        + request.getLine().productName() + ". El reembolso se decide allí.");
    }

    /** El comprador responde la solicitud de información abierta dentro de las 24 h. */
    public void answerInformation(Long buyerAccountId, Long returnId, String text) {
        ReturnRequest request = repository.lockById(returnId)
                .filter(found -> found.getBuyerAccountId().equals(buyerAccountId))
                .orElseThrow(ReturnQueryService::notFound);
        LocalDateTime now = now();
        ReturnRequest.InformationAnswered answered = request.answerInformation(buyerAccountId, text, now);
        repository.answerInformationRequest(answered.request().id(), answered.text(), answered.respondedAt());
        repository.save(request);
        recorder.record(request, answered.event(), now);
        recorder.notifyStore(request, "INFORMATION_ANSWERED", "INFORMATION_ANSWERED-" + answered.request().id(),
                "El comprador respondió", "El comprador respondió tu solicitud de información sobre la devolución #"
                        + request.getId() + ".");
    }

    private ReturnRequest ofStore(Long storeId, Long returnId) {
        return repository.lockById(returnId).filter(found -> found.getStoreId().equals(storeId))
                .orElseThrow(ReturnQueryService::notFound);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
