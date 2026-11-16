package com.transformersas.marketplace.users.domain.port;

import com.transformersas.marketplace.users.domain.model.Role;

/**
 * Si una cuenta tiene una restricción administrativa vigente (CU-22) que le impide usar un rol. {@code users} no
 * conoce las restricciones: solo este puerto, que implementa un adaptador del módulo que las guarda.
 */
public interface AccountRestrictionReader {

    /** Vigente sobre {@code role} o sobre toda la cuenta. */
    boolean isRestricted(Long accountId, Role role);
}
