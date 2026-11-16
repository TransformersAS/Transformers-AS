package com.transformersas.marketplace.restrictions.domain.port;

import com.transformersas.marketplace.users.domain.model.Role;

import java.util.Set;

/** Lo que CU-22 necesita saber de una cuenta para buscarla y evaluarla: nunca su contraseña. */
public record AccountDirectoryEntry(Long id, String email, boolean active, Set<Role> roles) {
}
