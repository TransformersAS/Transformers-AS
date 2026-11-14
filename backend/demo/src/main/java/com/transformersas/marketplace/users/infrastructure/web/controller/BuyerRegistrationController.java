package com.transformersas.marketplace.users.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.users.application.usecase.*;
import com.transformersas.marketplace.users.infrastructure.web.request.BuyerRegistrationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/buyers")
public class BuyerRegistrationController {
    public record EnableRequest(@AssertTrue(message="Acepta los términos") boolean acceptTerms,
                                @AssertTrue(message="Acepta la política de privacidad") boolean acceptPrivacy) implements com.transformersas.marketplace.shared.web.StrictJsonRequest { }
    private final RegisterBuyerAccount register;
    private final EnableBuyerRole enable;
    public BuyerRegistrationController(RegisterBuyerAccount register, EnableBuyerRole enable) { this.register=register; this.enable=enable; }
    @GetMapping("/terms") public BuyerTerms.Notices terms() { return BuyerTerms.current(); }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public RegisterBuyerAccount.Result register(@Valid @RequestBody BuyerRegistrationRequest request) { return register.execute(new RegisterBuyerAccount.Input(request.fullName(), request.birthDate(), request.email(),
                request.phone(), request.password(), request.passwordConfirmation(), request.acceptTerms(), request.acceptPrivacy())); }
    @PostMapping("/enable")
    public EnableBuyerRole.Result enable(@AuthenticationPrincipal AccountPrincipal principal, @Valid @RequestBody EnableRequest request) {
        return enable.execute(principal.accountId(), request.acceptTerms(), request.acceptPrivacy());
    }
}
