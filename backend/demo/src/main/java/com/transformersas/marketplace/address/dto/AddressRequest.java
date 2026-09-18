package com.transformersas.marketplace.address.dto;

import jakarta.validation.constraints.*;

public record AddressRequest(
        @NotBlank @Size(max = 255) String recipientName,
        @NotBlank @Size(max = 255) String street,
        @NotBlank @Size(max = 255) String city,
        @NotBlank @Size(max = 255) String department,
        @Size(max = 50) @Pattern(regexp = "[A-Za-z0-9 -]*") String postalCode,
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "[+]?[0-9][0-9 ()-]*") String phone
) {}
