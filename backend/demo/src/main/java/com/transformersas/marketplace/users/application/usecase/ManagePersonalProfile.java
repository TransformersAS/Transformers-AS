package com.transformersas.marketplace.users.application.usecase;

import com.transformersas.marketplace.users.domain.model.PersonalProfile;
import com.transformersas.marketplace.users.domain.repository.PersonalProfileRepository;
import com.transformersas.marketplace.users.application.port.EmailChangeNotifier;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.LocalDate;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ManagePersonalProfile {
    public record Update(String fullName,LocalDate birthDate,String email,String phone,boolean promotions,boolean marketplaceNews,
                         String currentPassword,long version) {
        @Override public String toString() { return "Actualización de perfil [datos reservados]"; }
    }
    public record Saved(PersonalProfile profile,boolean verificationDeliveryFailed) { }
    public record Eligibility(boolean allowed,List<PersonalProfileRepository.Blocker> blockers,String consequences) { }
    private record Delivery(String email,String token) { }
    private final PersonalProfileRepository repository;
    private final PasswordEncoder encoder;
    private final EmailChangeNotifier notifier;
    private final TransactionTemplate transaction;
    private final TransactionTemplate closing;
    private static final ExecutorService MAIL=Executors.newVirtualThreadPerTaskExecutor();
    private final SecureRandom random=new SecureRandom();
    public ManagePersonalProfile(PersonalProfileRepository repository,PasswordEncoder encoder,EmailChangeNotifier notifier,PlatformTransactionManager manager) {
        this.repository=repository;this.encoder=encoder;this.notifier=notifier;
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        closing=new TransactionTemplate(manager);closing.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    private PersonalProfileRepository.Identity active(Long id,boolean lock) {
        var identity=repository.identity(id,lock);
        if(!"ACTIVA".equals(identity.status())) throw BusinessException.unauthenticated("ACCOUNT_INACTIVE","La cuenta ya no está activa");
        return identity;
    }
    private void password(PersonalProfileRepository.Identity identity,String password) {
        if(password==null || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length>72 || !encoder.matches(password,identity.passwordHash()))
            throw BusinessException.forbidden("CURRENT_PASSWORD_INVALID","La contraseña actual es incorrecta");
    }
    private static void version(long requested,long current) {
        if(requested!=current) throw BusinessException.conflict("PROFILE_CHANGED","El perfil cambió en otra sesión. Recarga antes de confirmar tus cambios.");
    }
    public PersonalProfile get(Long id) {return transaction.execute(s->{active(id,false);return repository.profile(id);});}
    public Saved update(Long id,Update update) {
        Delivery delivery=transaction.execute(s->{
            var identity=active(id,true);var previous=repository.profile(id);version(update.version(),previous.version());
            String email=update.email().strip().toLowerCase(Locale.ROOT);
            boolean emailChange=!email.equals(identity.email());
            boolean personalChange=emailChange || !Objects.equals(previous.fullName(),update.fullName().strip())
                || !Objects.equals(previous.birthDate(),update.birthDate()) || !Objects.equals(previous.phone(),update.phone().strip());
            if(personalChange) password(identity,update.currentPassword());
            if(emailChange && repository.emailOccupied(id,email)) throw emailOccupied();
            repository.update(id,update.fullName(),update.birthDate(),update.phone(),update.promotions(),update.marketplaceNews());
            return emailChange ? issue(identity,email) : null;
        });
        boolean failed=delivery!=null && !deliver(delivery);
        return new Saved(get(id),failed);
    }
    private Delivery issue(PersonalProfileRepository.Identity identity,String next) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.pendingEmail(identity.id(),identity.email(),next,hash(token),hash(identity.passwordHash()));
        return new Delivery(next,token);
    }
    public Saved resend(Long id,String currentPassword) {
        Delivery delivery=transaction.execute(s->{
            var identity=active(id,true);password(identity,currentPassword);
            var pending=repository.pendingEmail(id);
            if(pending==null) throw BusinessException.conflict("NO_PENDING_EMAIL_CHANGE","No tienes un cambio de correo pendiente");
            if(repository.emailOccupied(id,pending.newEmail())) throw emailOccupied();
            return issue(identity,pending.newEmail());
        });
        return new Saved(get(id),!deliver(delivery));
    }
    public void confirm(Long id,String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
        try {
            transaction.executeWithoutResult(s->{
                var identity=active(id,true);var pending=repository.pendingEmail(id);
                if(pending==null || !identity.email().equals(pending.previousEmail())
                    || !hash(identity.passwordHash()).equals(pending.credentialFingerprint())
                    || !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),pending.tokenHash().getBytes(StandardCharsets.US_ASCII))) throw invalidToken();
                if(repository.emailOccupied(id,pending.newEmail())) throw emailOccupied();
                if(!repository.consumeEmail(id,hash(token))) throw invalidToken();
                repository.confirmEmail(id,pending.newEmail(),identity.email());
            });
        } catch(DataIntegrityViolationException duplicate) { throw emailOccupied(); }
    }
    public Eligibility eligibility(Long id) {
        return transaction.execute(s->{active(id,false);return eligibilityFor(id);});
    }
    private Eligibility eligibilityFor(Long id) {
        var blockers=repository.blockers(id);
        return new Eligibility(blockers.isEmpty(),blockers,
            "Se desactivará la cuenta y se cerrarán sus sesiones. La tienda y sus ofertas dejarán de estar disponibles. Se conservarán pedidos, pagos, reclamaciones, devoluciones y referencias históricas. No se garantiza una reactivación automática.");
    }
    public void close(Long id,String currentPassword,long requestedVersion) {
        closing.executeWithoutResult(s->{
            var identity=active(id,true);password(identity,currentPassword);version(requestedVersion,repository.profile(id).version());
            var result=eligibilityFor(id);
            if(!result.allowed()) throw BusinessException.conflict("ACCOUNT_HAS_PENDING_PROCESSES","Debes finalizar los procesos pendientes antes de cerrar la cuenta",result.blockers());
            repository.deactivate(id,identity.email());
        });
    }
    private boolean deliver(Delivery delivery) {
        var job=MAIL.submit(()->notifier.send(delivery.email(),delivery.token()));
        try {job.get(10,TimeUnit.SECONDS);return true;}
        catch(InterruptedException e) {job.cancel(true);Thread.currentThread().interrupt();return false;}
        catch(ExecutionException | TimeoutException e) {
            job.cancel(true);org.slf4j.LoggerFactory.getLogger(getClass()).warn("No se pudo entregar la confirmación de cambio de correo");return false;
        }
    }
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException("SHA-256 no disponible",e);}
    }
    private static BusinessException invalidToken() {return BusinessException.invalid("INVALID_EMAIL_CHANGE_TOKEN","El código de cambio de correo es inválido, venció o ya fue utilizado. Solicita uno nuevo.");}
    private static BusinessException emailOccupied() {return BusinessException.conflict("EMAIL_ALREADY_REGISTERED","El correo no está disponible para esta cuenta");}
}
