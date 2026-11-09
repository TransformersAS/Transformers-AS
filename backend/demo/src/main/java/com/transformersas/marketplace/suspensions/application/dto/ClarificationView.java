package com.transformersas.marketplace.suspensions.application.dto;

import com.transformersas.marketplace.suspensions.domain.model.ClarificationRequest;

/** Una aclaración con el estado que corresponde ahora: una abierta con el plazo vencido se muestra como vencida. */
public record ClarificationView(ClarificationRequest request, ClarificationRequest.Status status) {
}
