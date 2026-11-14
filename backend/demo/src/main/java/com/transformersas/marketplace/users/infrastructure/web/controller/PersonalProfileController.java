package com.transformersas.marketplace.users.infrastructure.web.controller;

import com.transformersas.marketplace.users.application.usecase.ManagePersonalProfile;
import com.transformersas.marketplace.users.domain.model.PersonalProfile;
import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.shared.web.StrictJsonRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.*;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account/profile")
public class PersonalProfileController {
    public record UpdateRequest(
        @NotBlank(message="Escribe tu nombre completo") @Size(max=150,message="El nombre admite hasta 150 caracteres") String fullName,
        @NotNull(message="Indica tu fecha de nacimiento") @PastOrPresent(message="La fecha no puede ser futura") LocalDate birthDate,
        @NotBlank(message="Escribe tu correo") @Email(message="Escribe un correo válido") @Size(max=254) String email,
        @NotBlank(message="Escribe tu teléfono") @Size(max=32) @Pattern(regexp="[+]?[0-9][0-9 ()-]{5,30}[0-9]",message="Escribe un teléfono válido") String phone,
        boolean promotions,boolean marketplaceNews,String currentPassword,@NotNull @PositiveOrZero Long version,
        @AssertTrue(message="Confirma que deseas guardar los cambios") boolean confirmChanges
    ) implements StrictJsonRequest {
        @Override public String toString() {return "Petición de perfil [datos reservados]";}
    }
    public record PasswordRequest(String currentPassword) implements StrictJsonRequest {
        @Override public String toString() {return "Confirmación de identidad [datos reservados]";}
    }
    public record TokenRequest(String token) implements StrictJsonRequest {
        @Override public String toString() {return "Confirmación de correo [datos reservados]";}
    }
    public record CloseRequest(String currentPassword,@NotNull @PositiveOrZero Long version,
            @AssertTrue(message="Confirma expresamente el cierre de la cuenta") boolean confirmClosure) implements StrictJsonRequest {
        @Override public String toString() {return "Cierre de cuenta [datos reservados]";}
    }
    private final ManagePersonalProfile profile;
    private final SecurityContextRepository contexts;
    public PersonalProfileController(ManagePersonalProfile profile,SecurityContextRepository contexts) {this.profile=profile;this.contexts=contexts;}
    @GetMapping public PersonalProfile get(@AuthenticationPrincipal AccountPrincipal account) {return profile.get(account.accountId());}
    @PutMapping public ManagePersonalProfile.Saved update(@AuthenticationPrincipal AccountPrincipal account,@Valid @RequestBody UpdateRequest r) {
        return profile.update(account.accountId(),new ManagePersonalProfile.Update(r.fullName(),r.birthDate(),r.email(),r.phone(),r.promotions(),r.marketplaceNews(),r.currentPassword(),r.version()));
    }
    @PostMapping("/email/resend") public ManagePersonalProfile.Saved resend(@AuthenticationPrincipal AccountPrincipal account,@RequestBody PasswordRequest r) {return profile.resend(account.accountId(),r.currentPassword());}
    @PostMapping("/email/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal AccountPrincipal account,@RequestBody TokenRequest r,HttpServletRequest request,HttpServletResponse response) {
        profile.confirm(account.accountId(),r.token());logout(request,response);
    }
    @GetMapping("/closure") public ManagePersonalProfile.Eligibility eligibility(@AuthenticationPrincipal AccountPrincipal account) {return profile.eligibility(account.accountId());}
    @PostMapping("/closure") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@AuthenticationPrincipal AccountPrincipal account,@Valid @RequestBody CloseRequest r,HttpServletRequest request,HttpServletResponse response) {
        profile.close(account.accountId(),r.currentPassword(),r.version());logout(request,response);
    }
    private void logout(HttpServletRequest request,HttpServletResponse response) {
        var handler=new SecurityContextLogoutHandler();handler.setSecurityContextRepository(contexts);
        handler.logout(request,response,SecurityContextHolder.getContext().getAuthentication());
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String,String>> persistenceError() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code","PROFILE_SAVE_UNAVAILABLE","message","No se pudieron guardar los cambios. La información anterior se conserva; vuelve a intentarlo."));
    }
}
