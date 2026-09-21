package com.transformersas.marketplace.returns.application.dto;

import com.transformersas.marketplace.returns.domain.model.ReturnEventType;
import com.transformersas.marketplace.returns.domain.model.ReturnOrigin;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lo que las devoluciones muestran al comprador y al vendedor (RF-048, RF-051, RF-105, RF-106). Nunca incluyen la
 * identidad de la otra parte ni los ids de las cuentas: quien pide la información ya sabe con quién trata por su propio rol.
 */
public final class ReturnViews {
    private ReturnViews() {
    }

    /** Fila de un listado; no incluye el detalle ni imágenes. */
    public record Summary(Long id, Long orderId, Long orderItemId, String productName, int quantity,
                          BigDecimal refundAmount, ReturnStatus status, String reasonLabel, ReturnOrigin origin,
                          LocalDateTime createdAt, LocalDateTime updatedAt, boolean sellerDecisionOverdue,
                          boolean methodSelectionOverdue, boolean awaitingBuyerResponse, boolean pickupBlocked) {
    }

    public record Decision(String note, LocalDateTime decidedAt) {
    }

    public record InformationView(Long id, String message, LocalDateTime requestedAt, LocalDateTime dueAt,
                                  String status, boolean expired, String responseText, LocalDateTime respondedAt) {
    }

    public record EvidenceView(int ordinal, String fileName, String contentType, long sizeBytes) {
    }

    /** Un hecho de la línea de tiempo: quién (rol), qué y cuándo. {@code actor}: BUYER, SELLER o SYSTEM. */
    public record TimelineView(ReturnEventType type, ReturnStatus from, ReturnStatus to, String actor, String details,
                               LocalDateTime at) {
    }

    /** Detalle de una devolución con su línea de tiempo (RF-051). */
    public record Detail(Long id, Long orderId, Long orderItemId, Long productId, String productName, int quantity,
                         BigDecimal unitPrice, BigDecimal refundAmount, ReturnStatus status, String reason,
                         String reasonLabel, String description, ReturnOrigin origin, Long originClaimId,
                         LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime returnWindowEndsAt,
                         Decision decision, String returnMethodCode, LocalDateTime inspectionDueAt,
                         boolean problemReported, String problemDescription, Long claimId, boolean pickupBlocked,
                         boolean sellerDecisionOverdue, boolean methodSelectionOverdue, boolean awaitingBuyerResponse,
                         List<InformationView> informationRequests, List<EvidenceView> evidences,
                         List<TimelineView> timeline) {
    }

    /** Un método de retorno que logística ofrece ahora. */
    public record Method(String code, String label) {
    }

    /** Resultado de solicitar una devolución; {@code duplicate} si esa línea ya tenía una (A3) y se devuelve esa. */
    public record Requested(Long id, Long orderItemId, ReturnStatus status, boolean duplicate, int evidenceCount,
                            LocalDateTime createdAt) {
    }

    /** Una línea de un pedido entregado y si se puede devolver (RF-048, A1). */
    public record EligibleLine(Long orderItemId, Long productId, String productName, int quantity,
                               BigDecimal unitPrice, BigDecimal refundAmount, boolean eligible, String ineligibleCode,
                               String ineligibleMessage, LocalDateTime returnWindowEndsAt, Long existingReturnId,
                               ReturnStatus existingReturnStatus) {
    }

    public record EligibleOrder(Long orderId, LocalDateTime deliveredAt, List<EligibleLine> lines) {
    }
}
