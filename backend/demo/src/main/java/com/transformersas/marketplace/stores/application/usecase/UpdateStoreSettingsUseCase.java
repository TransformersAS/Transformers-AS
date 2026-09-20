package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.dto.StoreSettingsView;
import com.transformersas.marketplace.stores.application.dto.UpdateStoreSettingsCommand;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreModificationPolicy;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Guarda la configuración de la tienda (RF-058 a RF-060). Una tienda restringida o suspendida no se modifica (A8).
 * El perfil, la política y la auditoría se escriben en una sola transacción: si algo falla no queda la tienda a
 * medias (A10). La auditoría solo registra qué campos cambiaron, nunca sus valores.
 */
@Component
public class UpdateStoreSettingsUseCase {

    private final StoreRepository stores;
    private final StoreModificationPolicy modification;
    private final StoreSettingsValidator validator;
    private final StoreSettingsAssembler assembler;
    private final AuditRecorder audit;

    UpdateStoreSettingsUseCase(StoreRepository stores, StoreModificationPolicy modification,
                               StoreSettingsValidator validator, StoreSettingsAssembler assembler,
                               AuditRecorder audit) {
        this.stores = stores;
        this.modification = modification;
        this.validator = validator;
        this.assembler = assembler;
        this.audit = audit;
    }

    @Transactional
    public StoreSettingsView execute(UpdateStoreSettingsCommand command) {
        Store current = stores.findById(command.storeId())
                .orElseThrow(() -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe"));
        modification.permissionFor(command.storeId()).requireAllowed();
        validator.validate(command.storeId(), command.profile(), command.policy());

        Store edited = new Store(current.id(), current.ownerAccountId(), command.profile(), command.policy(),
                current.status(), current.statusReason(), command.expectedVersion());
        Store saved = stores.save(edited);

        audit.record(ActorType.SELLER, command.actorId(), "STORE_SETTINGS_UPDATED", "STORE", saved.id(),
                AuditOutcome.SUCCESS, Map.of("changed", changedFields(current, saved)));
        return assembler.assemble(saved);
    }

    private static List<String> changedFields(Store before, Store after) {
        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "name", before.profile().name(), after.profile().name());
        addIfChanged(changed, "description", before.profile().description(), after.profile().description());
        addIfChanged(changed, "contactEmail", before.profile().contactEmail(), after.profile().contactEmail());
        addIfChanged(changed, "contactPhone", before.profile().contactPhone(), after.profile().contactPhone());
        addIfChanged(changed, "businessHours", before.profile().businessHours(), after.profile().businessHours());
        addIfChanged(changed, "returnWindowDays", before.policy().returnWindowDays(),
                after.policy().returnWindowDays());
        addIfChanged(changed, "policyText", before.policy().text(), after.policy().text());
        return changed;
    }

    private static void addIfChanged(List<String> changed, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }
}
