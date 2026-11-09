package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionStatus;
import com.transformersas.marketplace.suspensions.domain.repository.StoreSuspensionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * A5: termina las suspensiones cuyo fin ya llegó y reactiva sus tiendas si no hay otra restricción (pasos 18 a 20).
 * Cada suspensión se cierra en su propia transacción: una que falle no deshace las demás. Todas las réplicas corren
 * el barrido; el cierre es compare-and-set, así que si otra réplica (o un administrador) se adelantó esta la salta.
 */
@Service
public class ExpireDueSuspensionsUseCase {

    static final int BATCH = 50;
    static final String REASON = "Se cumplió la fecha de finalización de la suspensión";

    private static final Logger log = LoggerFactory.getLogger(ExpireDueSuspensionsUseCase.class);

    private final StoreSuspensionRepository suspensions;
    private final SuspensionClosing closing;
    private final TransactionTemplate transaction;

    public ExpireDueSuspensionsUseCase(StoreSuspensionRepository suspensions, SuspensionClosing closing,
                                       PlatformTransactionManager transactions) {
        this.suspensions = suspensions;
        this.closing = closing;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Devuelve cuántas suspensiones terminó esta pasada. */
    public int runOnce() {
        LocalDateTime now = LocalDateTime.now();
        int ended = 0;
        for (StoreSuspension due : suspensions.findDue(now, BATCH)) {
            try {
                transaction.executeWithoutResult(status ->
                        closing.close(due, SuspensionStatus.EXPIRED, null, REASON, now));
                ended++;
            } catch (BusinessException alreadyHandled) {
                log.info("Suspensión {} ya cerrada por otro proceso: {}", due.id(), alreadyHandled.code());
            } catch (RuntimeException failure) {
                // Se reintenta en la próxima pasada; no detiene el cierre de las demás.
                log.warn("No se pudo terminar la suspensión {}", due.id(), failure);
            }
        }
        if (ended > 0) {
            log.info("Suspensiones terminadas automáticamente: {}", ended);
        }
        return ended;
    }
}
