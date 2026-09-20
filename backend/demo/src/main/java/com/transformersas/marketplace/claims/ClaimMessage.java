package com.transformersas.marketplace.claims;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Una entrada del hilo de una reclamación: un mensaje, una petición de información, una propuesta o una decisión. */
@Embeddable
@Getter
@NoArgsConstructor
public class ClaimMessage {

    public enum Author { BUYER, SELLER, SUPPORT }

    public enum Kind { MESSAGE, INFO_REQUEST, PROPOSAL, ESCALATION, DECISION }

    @Enumerated(EnumType.STRING)
    @Column(name = "author", nullable = false, length = 10)
    private Author author;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 15)
    private Kind kind;

    @Column(name = "author_account_id", nullable = false)
    private Long authorAccountId;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ClaimMessage(Author author, Kind kind, Long authorAccountId, String message) {
        this.author = author;
        this.kind = kind;
        this.authorAccountId = authorAccountId;
        this.message = message;
        this.createdAt = LocalDateTime.now();
    }
}
