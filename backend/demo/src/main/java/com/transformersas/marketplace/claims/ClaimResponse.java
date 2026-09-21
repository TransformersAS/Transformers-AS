package com.transformersas.marketplace.claims;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Reclamación con su hilo, tal como la ven el comprador, el vendedor y soporte. */
public record ClaimResponse(Long id, Long orderId, Long productId, String productName, BigDecimal itemTotal,
                            Long storeId, String description, ClaimStatus status, List<String> evidenceUrls,
                            String proposalText, BigDecimal proposedRefund, ClaimResolution resolution,
                            String resolutionNote, BigDecimal refundAmount, String refundStatus,
                            LocalDateTime createdAt, LocalDateTime updatedAt, List<MessageResponse> messages) {

    public record MessageResponse(ClaimMessage.Author author, ClaimMessage.Kind kind, String message,
                                  LocalDateTime createdAt) {
    }

    public static ClaimResponse from(Claim claim) {
        return new ClaimResponse(claim.getId(), claim.getOrderId(), claim.getProductId(), claim.getProductName(),
                claim.getItemTotal(), claim.getStoreId(), claim.getDescription(), claim.getStatus(),
                List.copyOf(claim.getEvidenceUrls()), claim.getProposalText(), claim.getProposedRefund(),
                claim.getResolution(), claim.getResolutionNote(), claim.getRefundAmount(), claim.getRefundStatus(),
                claim.getCreatedAt(), claim.getUpdatedAt(),
                claim.getMessages().stream()
                        .map(message -> new MessageResponse(message.getAuthor(), message.getKind(),
                                message.getMessage(), message.getCreatedAt()))
                        .toList());
    }
}
