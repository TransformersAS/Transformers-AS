package com.transformersas.marketplace.reports.application.dto;

public record DecisionResponse(Long decisionId, String decision, String measureResult, String caseStatus,
                               String contentState) {
}
