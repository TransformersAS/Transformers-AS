package com.transformersas.marketplace.users.domain.repository;

import java.time.LocalDate;

/** Buyer onboarding persistence; personal data stays outside security identities. */
public interface BuyerRegistrationRepository {
    void saveProfile(Long id, String name, LocalDate birthDate, String phone);
    void consent(Long id, String version);
    boolean addBuyerRole(Long id);
}
