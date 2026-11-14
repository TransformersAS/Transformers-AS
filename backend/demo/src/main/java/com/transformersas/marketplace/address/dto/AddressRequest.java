package com.transformersas.marketplace.address.dto;

import jakarta.validation.constraints.*;

public record AddressRequest(
        @NotBlank(message="Escribe el destinatario") @Size(max = 255) String recipientName,
        @NotBlank(message="Escribe la dirección") @Size(max = 255) String street,
        @NotBlank(message="Escribe la ciudad") @Size(max = 255) String city,
        @NotBlank(message="Escribe el departamento") @Size(max = 255) String department,
        @Size(max = 50) @Pattern(regexp = "[A-Za-z0-9 -]*") String postalCode,
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "(?=(?:\\D*\\d){7,15}\\D*$)[+]?[0-9][0-9 ()-]*", message="Ingresa un teléfono de 7 a 15 dígitos") String phone
) {}
