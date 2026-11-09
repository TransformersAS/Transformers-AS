package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.suspensions.application.dto.ClarificationView;
import com.transformersas.marketplace.suspensions.application.dto.SuspensionView;
import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionChange;
import com.transformersas.marketplace.suspensions.domain.repository.ClarificationRepository;
import com.transformersas.marketplace.suspensions.domain.repository.StoreSuspensionRepository;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Suspensiones y aclaraciones de una tienda tal como las muestran el administrador y el vendedor. */
@Component
public class SuspensionHistory {

    private final StoreSuspensionRepository suspensions;
    private final ClarificationRepository clarifications;

    public SuspensionHistory(StoreSuspensionRepository suspensions, ClarificationRepository clarifications) {
        this.suspensions = suspensions;
        this.clarifications = clarifications;
    }

    /** De la más reciente a la más antigua, cada una con su historia. */
    List<SuspensionView> suspensionsOf(Long storeId) {
        List<StoreSuspension> all = suspensions.findByStore(storeId);
        Map<Long, List<SuspensionChange>> changes =
                suspensions.findChanges(all.stream().map(StoreSuspension::id).toList());
        return all.stream().map(suspension -> new SuspensionView(suspension,
                changes.getOrDefault(suspension.id(), List.of()))).toList();
    }

    List<ClarificationView> clarificationsOf(Long storeId, LocalDateTime now) {
        return clarifications.findByStore(storeId).stream()
                .map(request -> new ClarificationView(request, request.effectiveStatus(now))).toList();
    }

    static SuspensionView active(List<SuspensionView> views) {
        return views.stream().filter(view -> view.suspension().isActive()).findFirst().orElse(null);
    }
}
