package com.transformersas.marketplace.users.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Perfil único de la identidad; nunca depende del rol activo ni contiene credenciales. */
public record PersonalProfile(String fullName, LocalDate birthDate, String email, String phone,
        boolean promotions, boolean marketplaceNews, boolean essentialCommunications, long version,
        String pendingEmail, LocalDateTime pendingEmailExpiresAt) { }
