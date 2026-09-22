package com.transformersas.marketplace.returns.application.usecase;

import com.transformersas.marketplace.returns.application.ReturnException;
import com.transformersas.marketplace.returns.application.ReturnViewAssembler;
import com.transformersas.marketplace.returns.application.dto.ReturnViews;
import com.transformersas.marketplace.returns.domain.eligibility.Ineligibility;
import com.transformersas.marketplace.returns.domain.eligibility.ReturnCandidate;
import com.transformersas.marketplace.returns.domain.eligibility.ReturnEligibility;
import com.transformersas.marketplace.returns.domain.model.ReturnLine;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.returns.domain.port.OrderForReturn;
import com.transformersas.marketplace.returns.domain.port.OrderForReturnReader;
import com.transformersas.marketplace.returns.domain.port.ReturnEvidenceStorage;
import com.transformersas.marketplace.returns.domain.port.ReturnEvidenceStorage.EvidenceContent;
import com.transformersas.marketplace.returns.domain.port.StoreReturnPolicyReader;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consultas de devoluciones para el comprador (RF-048, RF-051) y para la tienda (RF-105, RF-106). Todo se acota a su dueño:
 * una devolución de otro comprador o de otra tienda responde 404, igual que una inexistente, y las imágenes igual.
 */
@Service
@Transactional(readOnly = true)
public class ReturnQueryService {
    private final ReturnRequestRepository repository;
    private final OrderForReturnReader orders;
    private final StoreReturnPolicyReader storePolicy;
    private final ReturnEligibility eligibility;
    private final ReturnEvidenceStorage evidence;
    private final ReturnViewAssembler views;
    private final Clock clock;

    public ReturnQueryService(ReturnRequestRepository repository, OrderForReturnReader orders,
                              StoreReturnPolicyReader storePolicy, ReturnEligibility eligibility,
                              ReturnEvidenceStorage evidence, ReturnViewAssembler views, Clock clock) {
        this.repository = repository;
        this.orders = orders;
        this.storePolicy = storePolicy;
        this.eligibility = eligibility;
        this.evidence = evidence;
        this.views = views;
        this.clock = clock;
    }

    // ---------- Comprador ----------

    /** Los pedidos entregados del comprador con cada línea y si se puede devolver, o por qué no (RF-048, A1). */
    public List<ReturnViews.EligibleOrder> eligibleOrders(Long buyerAccountId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<OrderForReturn> delivered = orders.findByBuyer(buyerAccountId).stream()
                .filter(OrderForReturn::delivered).toList();
        Map<Long, ReturnRequest> existing = repository.findByOrderItemIds(delivered.stream()
                        .flatMap(order -> order.lines().stream()).map(ReturnLine::orderItemId).toList()).stream()
                .collect(Collectors.toMap(request -> request.getLine().orderItemId(), Function.identity()));
        Map<Long, Optional<Integer>> windows = new HashMap<>();
        return delivered.stream().map(order -> {
            Optional<Integer> window = windows.computeIfAbsent(order.storeId(), storePolicy::returnWindowDays);
            List<ReturnViews.EligibleLine> lines = order.lines().stream()
                    .map(line -> eligibleLine(order, line, window, existing.get(line.orderItemId()), now)).toList();
            return new ReturnViews.EligibleOrder(order.orderId(), order.deliveredAt(), lines);
        }).toList();
    }

    private ReturnViews.EligibleLine eligibleLine(OrderForReturn order, ReturnLine line, Optional<Integer> window,
                                                  ReturnRequest existing, LocalDateTime now) {
        LocalDateTime endsAt = window.isPresent() && order.deliveredAt() != null
                ? order.deliveredAt().plusDays(window.get()) : null;
        if (existing != null) {
            return new ReturnViews.EligibleLine(line.orderItemId(), line.productId(), line.productName(),
                    line.quantity(), line.unitPrice(), line.refundAmount(), false, "RETURN_ALREADY_REQUESTED",
                    "Este producto ya tiene una solicitud de devolución", endsAt, existing.getId(),
                    existing.getStatus());
        }
        Optional<Ineligibility> failure = window.isEmpty()
                ? Optional.of(new Ineligibility("RETURN_STORE_NOT_FOUND", "La tienda del pedido no existe"))
                : eligibility.firstFailure(new ReturnCandidate(order, line.orderItemId(), window.get(), now));
        return new ReturnViews.EligibleLine(line.orderItemId(), line.productId(), line.productName(), line.quantity(),
                line.unitPrice(), line.refundAmount(), failure.isEmpty(), failure.map(Ineligibility::code).orElse(null),
                failure.map(Ineligibility::message).orElse(null), endsAt, null, null);
    }

    public List<ReturnViews.Summary> buyerReturns(Long buyerAccountId) {
        return repository.findByBuyer(buyerAccountId).stream().map(views::summary).toList();
    }

    public ReturnViews.Detail buyerReturn(Long buyerAccountId, Long id) {
        return views.detail(ownedByBuyer(buyerAccountId, id));
    }

    public EvidenceContent buyerEvidence(Long buyerAccountId, Long id, int ordinal) {
        ownedByBuyer(buyerAccountId, id);
        return image(id, ordinal);
    }

    // ---------- Tienda ----------

    public List<ReturnViews.Summary> sellerReturns(Long storeId, ReturnStatus statusOrNull) {
        return repository.findByStore(storeId, statusOrNull).stream().map(views::summary).toList();
    }

    public ReturnViews.Detail sellerReturn(Long storeId, Long id) {
        return views.detail(ownedByStore(storeId, id));
    }

    public EvidenceContent sellerEvidence(Long storeId, Long id, int ordinal) {
        ownedByStore(storeId, id);
        return image(id, ordinal);
    }

    private ReturnRequest ownedByBuyer(Long buyerAccountId, Long id) {
        return repository.findById(id).filter(request -> request.getBuyerAccountId().equals(buyerAccountId))
                .orElseThrow(ReturnQueryService::notFound);
    }

    private ReturnRequest ownedByStore(Long storeId, Long id) {
        return repository.findById(id).filter(request -> request.getStoreId().equals(storeId))
                .orElseThrow(ReturnQueryService::notFound);
    }

    private EvidenceContent image(Long id, int ordinal) {
        return evidence.find(id, ordinal).orElseThrow(
                () -> ReturnException.notFound("RETURN_EVIDENCE_NOT_FOUND", "Imagen no encontrada"));
    }

    static ReturnException notFound() {
        return ReturnException.notFound("RETURN_NOT_FOUND", "Devolución no encontrada");
    }
}
