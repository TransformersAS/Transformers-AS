package com.transformersas.marketplace.users.domain.repository;

import com.transformersas.marketplace.users.domain.model.PersonalProfile;
import java.time.LocalDate;
import java.util.List;

/** Persistencia del perfil, sin introducir una segunda identidad. */
public interface PersonalProfileRepository {
    record Identity(Long id,String email,String passwordHash,String status) {
        @Override public String toString() { return "Identidad de perfil [datos reservados]"; }
    }
    record PendingEmail(String previousEmail,String newEmail,String tokenHash,String credentialFingerprint) { }
    record Blocker(String code,String message,long count) { }
    Identity identity(Long id,boolean lock);
    PersonalProfile profile(Long id);
    void update(Long id,String name,LocalDate birth,String phone,boolean promotions,boolean news);
    void pendingEmail(Long id,String oldEmail,String newEmail,String hash,String fingerprint);
    PendingEmail pendingEmail(Long id);
    boolean consumeEmail(Long id,String hash);
    boolean emailOccupied(Long id,String email);
    void confirmEmail(Long id,String email,String previousEmail);
    List<Blocker> blockers(Long id);
    void deactivate(Long id,String email);
}
