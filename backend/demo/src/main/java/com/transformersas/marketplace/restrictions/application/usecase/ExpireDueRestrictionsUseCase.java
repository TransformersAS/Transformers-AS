package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionStatus;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * A5: termina las restricciones temporales cuyo fin ya llegó (las permanentes nunca vencen). Cada una se cierra en
 * su propia transacción: una que falle no deshace las demás. Todas las réplicas corren el barrido; el cierre es
 * compare-and-set, así que si otra réplica (o un administrador) se adelantó esta la salta.
 */
@Service
public class ExpireDueRestrictionsUseCase {

    static final int BATCH = 50;
    static final String REASON = "Se cumplió la fecha de finalización de la restricción";

    private static final Logger log = LoggerFactory.getLogger(ExpireDueRestrictionsUseCase.class);

    private final AccountRestrictionRepository restrictions;
    private final RestrictionClosing closing;
    private final TransactionTemplate transaction;

    public ExpireDueRestrictionsUseCase(AccountRestrictionRepository restrictions, RestrictionClosing closing,
                                        PlatformTransactionManager transactions) {
        this.restrictions = restrictions;
        this.closing = closing;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Devuelve cuántas restricciones terminó esta pasada. */
    public int runOnce() {
        LocalDateTime now = LocalDateTime.now();
        int ended = 0;
        for (AccountRestriction due : restrictions.findDue(now, BATCH)) {
            try {
                transaction.executeWithoutResult(status ->
                        closing.close(due, RestrictionStatus.EXPIRED, null, REASON, now));
                ended++;
            } catch (BusinessException alreadyHandled) {
                log.info("Restricción {} ya cerrada por otro proceso: {}", due.id(), alreadyHandled.code());
            } catch (RuntimeException failure) {
                // Se reintenta en la próxima pasada; no detiene el cierre de las demás.
                log.warn("No se pudo terminar la restricción {}", due.id(), failure);
            }
        }
        if (ended > 0) {
            log.info("Restricciones terminadas automáticamente: {}", ended);
        }
        return ended;
    }
}
