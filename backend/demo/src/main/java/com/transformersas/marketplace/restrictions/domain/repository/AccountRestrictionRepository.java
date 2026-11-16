package com.transformersas.marketplace.restrictions.domain.repository;

import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionChange;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionStatus;
import com.transformersas.marketplace.users.domain.model.Role;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AccountRestrictionRepository {

    /**
     * Guarda una restricción nueva y devuelve su id. Si la cuenta ya tiene una vigente con el mismo alcance (la
     * cuenta completa, o el mismo rol) lanza BusinessException CONFLICT ACCOUNT_ALREADY_RESTRICTED (la base de datos
     * admite una sola por alcance).
     */
    Long insert(AccountRestriction restriction);

    Optional<AccountRestriction> findById(Long id);

    /** Todas las restricciones vigentes de la cuenta (la de toda la cuenta y las de cada rol), más recientes primero. */
    List<AccountRestriction> findActiveByAccount(Long accountId);

    /** Historia completa de la cuenta, de la más reciente a la más antigua. */
    List<AccountRestriction> findByAccount(Long accountId);

    /** Temporales vigentes cuyo fin ya llegó, hasta {@code limit}, las más vencidas primero. */
    List<AccountRestriction> findDue(LocalDateTime now, int limit);

    /** Indica si la cuenta tiene una restricción vigente sobre {@code role} o sobre toda la cuenta (RF-155). */
    boolean hasActiveRestriction(Long accountId, Role role);

    /** Cambia el fin si sigue vigente, temporal y en esa versión. Devuelve false si otro proceso la cambió antes. */
    boolean changeEnd(Long id, long version, LocalDateTime newEndsAt);

    /** La hace permanente (A7). Devuelve false si otro proceso la cambió o cerró antes. */
    boolean makePermanent(Long id, long version);

    /** La hace temporal con ese fin (A7). Devuelve false si otro proceso la cambió o cerró antes. */
    boolean makeTemporary(Long id, long version, LocalDateTime newEndsAt);

    /** Cierra si sigue vigente y en esa versión. Devuelve false si otro proceso la cambió o cerró antes. */
    boolean close(Long id, long version, RestrictionStatus status, LocalDateTime closedAt, Long closedBy,
                  String closeReason);

    /** Registra un hecho de la historia y devuelve su id. */
    Long addChange(RestrictionChange change);

    /** Historia de cada restricción indicada, en orden cronológico. */
    Map<Long, List<RestrictionChange>> findChanges(List<Long> restrictionIds);
}
