package com.transformersas.marketplace.claims;

import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.payments.application.dto.RefundCommand;
import com.transformersas.marketplace.payments.application.usecase.RequestRefundUseCase;
import com.transformersas.marketplace.payments.domain.model.Refund;
import com.transformersas.marketplace.shared.audit.ActorType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Reglas de las reclamaciones de compra (CU-13). Tres actores: el comprador la abre, la responde y la escala; el
 * vendedor de la tienda pide información o propone una solución; un agente de soporte decide las escaladas.
 *
 * <pre>
 * OPEN ──(vendedor pide info)──▶ INFO_REQUESTED ──(comprador responde)──▶ OPEN
 * OPEN / INFO_REQUESTED ──(vendedor propone)──▶ SOLUTION_PROPOSED ──(comprador acepta)──▶ RESOLVED
 * OPEN / INFO_REQUESTED / SOLUTION_PROPOSED ──(comprador escala)──▶ ESCALATED ──(soporte decide)──▶ RESOLVED
 * </pre>
 */
@Service
@Transactional
public class ClaimService {

    private final ClaimRepository claims;
    private final OrderRepository orders;
    private final RequestRefundUseCase refunds;

    public ClaimService(ClaimRepository claims, OrderRepository orders, RequestRefundUseCase refunds) {
        this.claims = claims;
        this.orders = orders;
        this.refunds = refunds;
    }

    // ================= Comprador =================

    /** Abre una reclamación sobre un producto de una compra del comprador. */
    public ClaimResponse open(Long buyerId, Long orderId, Long productId, String description,
                              List<String> evidenceUrls) {
        Order order = orders.findByIdAndAccountId(orderId, buyerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compra no encontrada"));
        if (order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.CANCELLATION_REQUESTED) {
            throw conflict("No se puede reclamar una compra cancelada");
        }
        OrderItem item = order.items().stream()
                .filter(candidate -> candidate.productId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El producto no pertenece a esa compra"));
        if (claims.existsByOrderIdAndProductIdAndStatusNot(orderId, productId, ClaimStatus.RESOLVED)) {
            throw conflict("Ya hay una reclamación abierta para este producto de la compra");
        }

        LocalDateTime now = LocalDateTime.now();
        Claim claim = new Claim();
        claim.setOrderId(orderId);
        claim.setProductId(productId);
        claim.setProductName(item.productName());
        claim.setItemTotal(item.subtotal());
        claim.setBuyerAccountId(buyerId);
        claim.setStoreId(order.storeId());
        claim.setDescription(description.strip());
        claim.setCreatedAt(now);
        claim.setUpdatedAt(now);
        if (evidenceUrls != null) {
            claim.getEvidenceUrls().addAll(evidenceUrls);
        }
        return ClaimResponse.from(claims.save(claim));
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> listForBuyer(Long buyerId) {
        return claims.findByBuyerAccountIdOrderByIdDesc(buyerId).stream().map(ClaimResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ClaimResponse getForBuyer(Long buyerId, Long id) {
        return ClaimResponse.from(findForBuyer(buyerId, id));
    }

    /** El comprador escribe en el caso; si el vendedor había pedido información, esto la responde. */
    public ClaimResponse addBuyerMessage(Long buyerId, Long id, String text) {
        Claim claim = findForBuyer(buyerId, id);
        requireNotResolved(claim);
        claim.addMessage(ClaimMessage.Author.BUYER, ClaimMessage.Kind.MESSAGE, buyerId, text.strip());
        if (claim.getStatus() == ClaimStatus.INFO_REQUESTED) {
            claim.setStatus(ClaimStatus.OPEN);
        }
        return ClaimResponse.from(claims.save(claim));
    }

    /** El comprador acepta la solución del vendedor: la reclamación termina y, si se ofreció, se reembolsa. */
    public ClaimResponse accept(Long buyerId, Long id) {
        Claim claim = findForBuyer(buyerId, id);
        requireStatus(claim, "No hay una solución propuesta para aceptar", ClaimStatus.SOLUTION_PROPOSED);
        claim.setStatus(ClaimStatus.RESOLVED);
        claim.setResolution(ClaimResolution.SOLUTION_ACCEPTED);
        claim.setResolutionNote(claim.getProposalText());
        claim.addMessage(ClaimMessage.Author.BUYER, ClaimMessage.Kind.DECISION, buyerId,
                "Acepté la solución propuesta");
        refundIfAny(claim, claim.getProposedRefund(), ActorType.BUYER, buyerId);
        return ClaimResponse.from(claims.save(claim));
    }

    /** Sin acuerdo con el vendedor, el comprador pide que la revise soporte. */
    public ClaimResponse escalate(Long buyerId, Long id, String reason) {
        Claim claim = findForBuyer(buyerId, id);
        requireStatus(claim, "Solo se puede escalar una reclamación que sigue abierta",
                ClaimStatus.OPEN, ClaimStatus.INFO_REQUESTED, ClaimStatus.SOLUTION_PROPOSED);
        claim.setStatus(ClaimStatus.ESCALATED);
        claim.addMessage(ClaimMessage.Author.BUYER, ClaimMessage.Kind.ESCALATION, buyerId,
                reason == null || reason.isBlank() ? "No llegamos a un acuerdo con el vendedor" : reason.strip());
        return ClaimResponse.from(claims.save(claim));
    }

    // ================= Vendedor =================

    @Transactional(readOnly = true)
    public List<ClaimResponse> listForStore(Long storeId) {
        return claims.findByStoreIdOrderByIdDesc(storeId).stream().map(ClaimResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ClaimResponse getForStore(Long storeId, Long id) {
        return ClaimResponse.from(findForStore(storeId, id));
    }

    /** El vendedor pide más información al comprador. */
    public ClaimResponse requestInfo(Long storeId, Long sellerId, Long id, String text) {
        Claim claim = findForStore(storeId, id);
        requireStatus(claim, "Solo se puede pedir información en una reclamación abierta", ClaimStatus.OPEN);
        claim.setStatus(ClaimStatus.INFO_REQUESTED);
        claim.addMessage(ClaimMessage.Author.SELLER, ClaimMessage.Kind.INFO_REQUEST, sellerId, text.strip());
        return ClaimResponse.from(claims.save(claim));
    }

    /** El vendedor propone una solución; puede ofrecer un reembolso de hasta lo pagado por ese producto. */
    public ClaimResponse propose(Long storeId, Long sellerId, Long id, String text, BigDecimal refund) {
        Claim claim = findForStore(storeId, id);
        requireStatus(claim, "Ya no se puede proponer una solución en esta reclamación",
                ClaimStatus.OPEN, ClaimStatus.INFO_REQUESTED, ClaimStatus.SOLUTION_PROPOSED);
        checkAmountWithinItemTotal(claim, refund);
        claim.setStatus(ClaimStatus.SOLUTION_PROPOSED);
        claim.setProposalText(text.strip());
        claim.setProposedRefund(refund == null || refund.signum() == 0 ? null : refund);
        claim.addMessage(ClaimMessage.Author.SELLER, ClaimMessage.Kind.PROPOSAL, sellerId, text.strip());
        return ClaimResponse.from(claims.save(claim));
    }

    // ================= Soporte =================

    @Transactional(readOnly = true)
    public List<ClaimResponse> listByStatus(ClaimStatus status) {
        return claims.findByStatusOrderByIdAsc(status).stream().map(ClaimResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ClaimResponse get(Long id) {
        return ClaimResponse.from(find(id));
    }

    /** El agente decide una reclamación escalada: reembolsar (total o parcial) o rechazarla. */
    public ClaimResponse resolve(Long agentId, Long id, ClaimResolution decision, BigDecimal amount, String note) {
        Claim claim = find(id);
        requireStatus(claim, "Solo soporte puede decidir una reclamación escalada", ClaimStatus.ESCALATED);
        if (decision == ClaimResolution.REFUND_GRANTED) {
            if (amount == null || amount.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el monto a reembolsar");
            }
            checkAmountWithinItemTotal(claim, amount);
        } else if (decision == ClaimResolution.REJECTED) {
            if (amount != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Una reclamación rechazada no lleva reembolso");
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La decisión debe ser REFUND_GRANTED o REJECTED");
        }

        claim.setStatus(ClaimStatus.RESOLVED);
        claim.setResolution(decision);
        claim.setResolutionNote(note.strip());
        claim.setResolvedByAccountId(agentId);
        claim.addMessage(ClaimMessage.Author.SUPPORT, ClaimMessage.Kind.DECISION, agentId, note.strip());
        refundIfAny(claim, amount, ActorType.SYSTEM, agentId);
        return ClaimResponse.from(claims.save(claim));
    }

    // ================= Métodos auxiliares =================

    private Claim find(Long id) {
        return claims.findById(id).orElseThrow(this::notFound);
    }

    private Claim findForBuyer(Long buyerId, Long id) {
        return claims.findByIdAndBuyerAccountId(id, buyerId).orElseThrow(this::notFound);
    }

    private Claim findForStore(Long storeId, Long id) {
        return claims.findByIdAndStoreId(id, storeId).orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Reclamación no encontrada");
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private void requireStatus(Claim claim, String message, ClaimStatus... allowed) {
        if (!Arrays.asList(allowed).contains(claim.getStatus())) {
            throw conflict(message);
        }
    }

    private void requireNotResolved(Claim claim) {
        if (claim.getStatus() == ClaimStatus.RESOLVED) {
            throw conflict("La reclamación ya terminó");
        }
    }

    /** Nadie puede ofrecer ni ordenar un reembolso mayor a lo que se pagó por ese producto. */
    private void checkAmountWithinItemTotal(Claim claim, BigDecimal amount) {
        if (amount != null && amount.compareTo(claim.getItemTotal()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El reembolso no puede superar lo pagado por el producto (" + claim.getItemTotal() + ")");
        }
    }

    /**
     * Reutiliza el reembolso idempotente de pagos: la clave "claim-{id}" garantiza que una misma reclamación nunca
     * reembolsa dos veces. Si la pasarela falla, la reclamación igual termina y el reembolso queda registrado para
     * reintentarse (refundStatus lo muestra).
     */
    private void refundIfAny(Claim claim, BigDecimal amount, ActorType actorType, Long actorId) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        claim.setRefundAmount(amount);
        Refund refund = refunds.execute(new RefundCommand(claim.getOrderId(), amount, "claim-" + claim.getId(),
                actorType, actorId));
        claim.setRefundStatus(refund.status().name());
    }
}
