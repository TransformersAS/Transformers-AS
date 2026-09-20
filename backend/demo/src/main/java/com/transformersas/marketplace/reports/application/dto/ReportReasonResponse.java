package com.transformersas.marketplace.reports.application.dto;

import com.transformersas.marketplace.reports.domain.model.ReportReason;

/** Motivo ofrecido al reportar; {@code purchaseProblem} indica que se atiende por reclamaciones y devoluciones. */
public record ReportReasonResponse(String code, String label, boolean purchaseProblem) {

    public static ReportReasonResponse from(ReportReason reason) {
        return new ReportReasonResponse(reason.name(), reason.label(), reason.purchaseProblem());
    }
}
