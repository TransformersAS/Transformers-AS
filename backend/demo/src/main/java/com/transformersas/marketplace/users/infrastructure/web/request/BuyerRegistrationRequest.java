package com.transformersas.marketplace.users.infrastructure.web.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record BuyerRegistrationRequest(
        @NotBlank(message="Escribe tu nombre completo") @Size(max=150, message="El nombre admite hasta 150 caracteres") String fullName,
        @NotNull(message="Indica tu fecha de nacimiento") @PastOrPresent(message="La fecha no puede ser futura") LocalDate birthDate,
        @NotBlank(message="Escribe tu correo") @Email(message="Escribe un correo válido") @Size(max=254, message="El correo admite hasta 254 caracteres") String email,
        @NotBlank(message="Escribe tu teléfono") @Size(max=32, message="El teléfono admite hasta 32 caracteres") @Pattern(regexp="[+]?[0-9][0-9 ()-]{5,30}[0-9]", message="Escribe un teléfono válido, con dígitos y separadores opcionales") String phone,
        @NotBlank(message="Escribe una contraseña") String password,
        @NotBlank(message="Confirma tu contraseña") String passwordConfirmation,
        @AssertTrue(message="Acepta los términos y condiciones") boolean acceptTerms,
        @AssertTrue(message="Acepta la política de privacidad") boolean acceptPrivacy) implements com.transformersas.marketplace.shared.web.StrictJsonRequest {
    public BuyerRegistrationRequest {
        if (email != null) email = email.strip().toLowerCase(java.util.Locale.ROOT);
    }
    @Override public String toString() { return "BuyerRegistrationRequest[REDACTED]"; }
}
