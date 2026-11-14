package com.transformersas.marketplace.suspensions.application.dto;

import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionChange;

import java.util.List;

/** Una suspensión con su historia en orden cronológico. */
public record SuspensionView(StoreSuspension suspension, List<SuspensionChange> changes) {
}
