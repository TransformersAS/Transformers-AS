package com.transformersas.marketplace.sellers;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Registro de vendedores (CU-12). Las condiciones, el registro de una cuenta nueva y la verificación del correo son
 * públicos (ver SecurityConfiguration); habilitar el rol con una cuenta existente exige la sesión de esa cuenta.
 */
@RestController
@RequestMapping("/api/sellers")
public class SellerRegistrationController {

    /** acceptTerms debe ser true: aceptar las condiciones es obligatorio para vender. */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password,
            @NotBlank @Size(max = 100) String storeName,
            @AssertTrue(message = "Debes aceptar las condiciones para vender") boolean acceptTerms
    ) {
    }

    public record EnableRequest(
            @NotBlank @Size(max = 100) String storeName,
            @AssertTrue(message = "Debes aceptar las condiciones para vender") boolean acceptTerms
    ) {
    }

    public record VerifyRequest(@NotBlank @Size(max = 100) String token) {
    }

    private final SellerRegistrationService service;

    public SellerRegistrationController(SellerRegistrationService service) {
        this.service = service;
    }

    @GetMapping("/terms")
    public SellerTerms.Terms terms() {
        return SellerTerms.current();
    }

    /** Sin cuenta: crea la cuenta y la tienda. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public SellerRegistrationService.Registration register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request.email(), request.password(), request.storeName());
    }

    /** Con cuenta: habilita el rol de vendedor con la misma cuenta y credenciales. */
    @PostMapping("/enable")
    @ResponseStatus(HttpStatus.CREATED)
    public SellerRegistrationService.Registration enable(@Valid @RequestBody EnableRequest request,
                                                         @AuthenticationPrincipal AccountPrincipal account) {
        return service.enable(account.accountId(), request.storeName());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyRequest request) {
        service.verifyEmail(request.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@AuthenticationPrincipal AccountPrincipal account) {
        service.resendVerification(account.accountId());
    }
}
