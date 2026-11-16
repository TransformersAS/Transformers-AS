package com.transformersas.marketplace.restrictions.application.dto;

import java.util.List;

/**
 * Lo que la propia persona ve de su cuenta (postcondición 5 y 8): sus restricciones vigentes, sin la identidad del
 * administrador que las aplicó, y las solicitudes de información que puede responder.
 */
public record AccountStanding(List<RestrictionView> activeRestrictions,
                              List<InformationRequestView> informationRequests) {
}
