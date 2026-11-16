package com.transformersas.marketplace.restrictions.application.dto;

import com.transformersas.marketplace.restrictions.domain.model.AccountInformationRequest;

/** Una solicitud de información con su estado efectivo (vencida o no, A4). */
public record InformationRequestView(AccountInformationRequest request, AccountInformationRequest.Status status) {
}
