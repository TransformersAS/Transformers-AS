package com.transformersas.marketplace.suspensions.application.dto;

import com.transformersas.marketplace.stores.domain.model.StoreStatus;

import java.util.List;

/**
 * Lo que el vendedor ve del estado de su tienda: la suspensión vigente (nula si no hay), lo que puede y no puede hacer,
 * sus suspensiones anteriores y las aclaraciones que le pidieron.
 */
public record SellerStanding(StoreStatus status, String statusReason, SuspensionView activeSuspension,
                             List<SuspensionView> suspensions, List<ClarificationView> clarifications,
                             List<String> blocked, List<String> allowed) {
}
