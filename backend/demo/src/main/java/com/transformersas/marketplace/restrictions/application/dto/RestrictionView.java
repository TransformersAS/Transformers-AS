package com.transformersas.marketplace.restrictions.application.dto;

import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionChange;

import java.util.List;

/** Una restricción con su historia completa (aplicación, cambios, cierre). */
public record RestrictionView(AccountRestriction restriction, List<RestrictionChange> changes) {
}
