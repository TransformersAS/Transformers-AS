package com.transformersas.marketplace.claims;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Reclamación de un comprador sobre un producto de una de sus compras (CU-13). */
@Entity
@Table(name = "claims")
@Getter
@Setter
@NoArgsConstructor
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    // Copia de lo que valía el producto en la compra, para no volver a consultar el pedido.
    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "item_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal itemTotal;

    @Column(name = "buyer_account_id", nullable = false)
    private Long buyerAccountId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimStatus status = ClaimStatus.OPEN;

    @Column(name = "proposal_text", length = 1000)
    private String proposalText;

    @Column(name = "proposed_refund", precision = 19, scale = 2)
    private BigDecimal proposedRefund;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ClaimResolution resolution;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "refund_amount", precision = 19, scale = 2)
    private BigDecimal refundAmount;

    // Resultado del reembolso (COMPLETED, PENDING o FAILED); nulo si no hubo reembolso.
    @Column(name = "refund_status", length = 10)
    private String refundStatus;

    @Column(name = "resolved_by_account_id")
    private Long resolvedByAccountId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ElementCollection
    @CollectionTable(name = "claim_evidences", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    @Column(name = "url", nullable = false, length = 500)
    private List<String> evidenceUrls = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "claim_messages", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "position")
    private List<ClaimMessage> messages = new ArrayList<>();

    /** Añade una entrada al hilo y marca la reclamación como actualizada. */
    public void addMessage(ClaimMessage.Author author, ClaimMessage.Kind kind, Long accountId, String text) {
        messages.add(new ClaimMessage(author, kind, accountId, text));
        updatedAt = LocalDateTime.now();
    }
}
