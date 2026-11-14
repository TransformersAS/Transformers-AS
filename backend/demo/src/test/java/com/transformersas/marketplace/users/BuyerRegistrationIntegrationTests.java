package com.transformersas.marketplace.users;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.auth.application.port.EmailVerificationNotifier;
import com.transformersas.marketplace.users.application.usecase.EnableBuyerRole;
import com.transformersas.marketplace.users.application.usecase.RegisterBuyerAccount;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BuyerRegistrationIntegrationTests extends AbstractIntegrationTest {
    @MockitoBean EmailVerificationNotifier notifier;
    @Autowired EnableBuyerRole enable;
    @Autowired RegisterBuyerAccount register;
    private final Map<String,String> tokens = new ConcurrentHashMap<>();
    @BeforeEach void mail() {
        tokens.clear();
        doAnswer(call -> { tokens.put(call.getArgument(0),call.getArgument(1)); return null; })
                .when(notifier).notifyVerification(anyString(),anyString());
    }
    private Map<String,Object> form() {
        var f=new HashMap<String,Object>();
        f.put("fullName","Ana Pérez"); f.put("birthDate","2000-05-20"); f.put("email"," NEW@example.com ");
        f.put("phone","+57 300 123 4567"); f.put("password",PASSWORD); f.put("passwordConfirmation",PASSWORD);
        f.put("acceptTerms",true); f.put("acceptPrivacy",true); return f;
    }
    private ResultActions publicPost(String path, Object body) throws Exception {
        var csrf=mvc.perform(get("/api/auth/csrf")).andReturn();
        var token=json.readTree(csrf.getResponse().getContentAsString());
        return mvc.perform(post(path).cookie(csrf.getResponse().getCookie("SESSION"))
                .header(token.get("headerName").asString(),token.get("token").asString())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }
    private ResultActions confirm(String token) throws Exception { return publicPost("/api/auth/email-verification/confirm",Map.of("token",token)); }
    private ResultActions resend() throws Exception { return publicPost("/api/auth/email-verification/resend",Map.of("email","new@example.com","password",PASSWORD)); }
    @Test void registersProfileConsentAndOnlyBuyerThenVerifiesAndLogsIn() throws Exception {
        mvc.perform(get("/api/buyers/terms")).andExpect(status().isOk()).andExpect(jsonPath("$.privacyVersion").exists());
        publicPost("/api/buyers/register",form()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.verificationDeliveryFailed").value(false));
        long id=accountIdOf("new@example.com");
        assertThat(jdbc.queryForObject("SELECT full_name FROM account_profiles WHERE account_id=?",String.class,id)).isEqualTo("Ana Pérez");
        assertThat(jdbc.queryForObject("SELECT birth_date FROM account_profiles WHERE account_id=?",java.sql.Date.class,id).toLocalDate()).isEqualTo(LocalDate.of(2000,5,20));
        assertThat(jdbc.queryForObject("SELECT phone FROM account_profiles WHERE account_id=?",String.class,id)).isEqualTo("+57 300 123 4567");
        assertThat(jdbc.queryForList("SELECT role FROM user_account_roles WHERE account_id=?",String.class,id)).containsExactly("COMPRADOR");
        assertThat(count("stores")).isEqualTo(1); assertThat(count("seller_terms_acceptances")).isZero();
        assertThat(count("buyer_consents")).isEqualTo(1);
        var hash=jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?",String.class,id);
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches(PASSWORD,hash)).isTrue();
        publicPost("/api/auth/login",Map.of()).andExpect(status().isUnauthorized());
        var csrf=mvc.perform(get("/api/auth/csrf")).andReturn(); var t=json.readTree(csrf.getResponse().getContentAsString());
        mvc.perform(post("/api/auth/login").cookie(csrf.getResponse().getCookie("SESSION"))
                .header(t.get("headerName").asString(),t.get("token").asString()).param("email","new@example.com").param("password",PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        confirm(tokens.get("new@example.com")).andExpect(status().isNoContent());
        var session=login("new@example.com");
        perform(session,get("/api/auth/me")).andExpect(jsonPath("$.activeRole").value("COMPRADOR"));
        perform(session,get("/api/orders")).andExpect(status().isOk());
        confirm(tokens.get("new@example.com")).andExpect(status().isBadRequest());
    }
    @Test void smtpFailureKeepsAccountAndResendReplacesExpiredInvalidAndUsedTokens() throws Exception {
        doThrow(new IllegalStateException("SMTP failure")).when(notifier).notifyVerification(anyString(),anyString());
        publicPost("/api/buyers/register",form()).andExpect(status().isCreated()).andExpect(jsonPath("$.verificationDeliveryFailed").value(true));
        assertThat(count("user_accounts")).isEqualTo(1);
        mail(); resend().andExpect(status().isAccepted()); String old=tokens.get("new@example.com");
        resend().andExpect(status().isAccepted()); String current=tokens.get("new@example.com");
        confirm(old).andExpect(status().isBadRequest()); confirm("invalid").andExpect(status().isBadRequest());
        jdbc.update("UPDATE email_verification_tokens SET expires_at=NOW()-INTERVAL 1 MINUTE");
        confirm(current).andExpect(status().isBadRequest());
        resend().andExpect(status().isAccepted()); confirm(tokens.get("new@example.com")).andExpect(status().isNoContent());
        publicPost("/api/buyers/register",form()).andExpect(status().isConflict()); assertThat(count("user_accounts")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"fullName","birthDate","email","phone","password","passwordConfirmation","acceptTerms","acceptPrivacy"})
    void requiredFieldsAndConsent(String field) throws Exception {
        var body=form(); body.put(field,field.startsWith("accept") ? false : "");
        publicPost("/api/buyers/register",body).andExpect(status().isBadRequest()); assertThat(count("user_accounts")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"email","phone","password","passwordConfirmation","birthDate","unicode"})
    void invalidFormatsAndPasswords(String field) throws Exception {
        var body=form();
        switch(field) {
            case "email" -> body.put(field,"invalid");
            case "phone" -> body.put(field,"abc1234");
            case "password" -> { body.put(field,"short");body.put("passwordConfirmation","short"); }
            case "passwordConfirmation" -> body.put(field,"DifferentPassword!");
            case "birthDate" -> body.put(field,"2999-01-01");
            case "unicode" -> { body.put("password","é".repeat(37));body.put("passwordConfirmation","é".repeat(37)); }
        }
        publicPost("/api/buyers/register",body).andExpect(status().isBadRequest()); assertThat(count("user_accounts")).isZero();
    }
    @Test void unicodePasswordAcceptsCodePointsWithinByteLimit() throws Exception {
        var body=form();body.put("password","😀".repeat(12));body.put("passwordConfirmation","😀".repeat(12));
        publicPost("/api/buyers/register",body).andExpect(status().isCreated());
    }
    @Test void enablePreservesAccountProfileCredentialsAndActiveRoleAndIsIdempotent() throws Exception {
        long id=createAccount("seller@example.com","VENDEDOR","SOPORTE");
        jdbc.update("INSERT INTO account_profiles VALUES (?,?,?,?)",id,"Existing",java.sql.Date.valueOf("1990-01-01"),"12345678");
        var before=jdbc.queryForMap("SELECT * FROM user_accounts WHERE id=?",id);
        var session=login("seller@example.com");
        perform(session,put("/api/auth/active-role").contentType("application/json").content("{\"role\":\"VENDEDOR\"}"));
        var body="{\"acceptTerms\":true,\"acceptPrivacy\":true}";
        perform(session,post("/api/buyers/enable").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.buyerRoleAdded").value(true));
        assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id=?",id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT full_name FROM account_profiles WHERE account_id=?",String.class,id)).isEqualTo("Existing");
        perform(session,get("/api/auth/me")).andExpect(jsonPath("$.roles.length()").value(3)).andExpect(jsonPath("$.activeRole").value("VENDEDOR"));
        perform(session,post("/api/buyers/enable").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.buyerRoleAdded").value(false));
        assertThat(count("buyer_consents")).isEqualTo(1); verifyNoInteractions(notifier);
    }
    @Test void anonymousCsrfAndForeignIdentityAreRejected() throws Exception {
        publicPost("/api/buyers/enable",Map.of("acceptTerms",true,"acceptPrivacy",true)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/buyers/register").contentType("application/json").content(json.writeValueAsString(form()))).andExpect(status().isForbidden());
        var session=sessionWithRole("own@example.com","VENDEDOR");long other=createAccount("other@example.com","SOPORTE");
        perform(session,post("/api/buyers/enable").contentType("application/json").content("{\"acceptTerms\":true,\"acceptPrivacy\":true,\"accountId\":"+other+"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account_roles WHERE account_id=? AND role='COMPRADOR'",Integer.class,other)).isZero();
        perform(session,post("/api/buyers/enable").contentType("application/json").content("{\"acceptTerms\":false,\"acceptPrivacy\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/buyers/enable").cookie(session.cookie()).contentType("application/json").content("{\"acceptTerms\":true,\"acceptPrivacy\":true}"))
                .andExpect(status().isForbidden());
    }
    @Test void concurrentRegistrationAndRoleEnablingDoNotDuplicateRows() throws Exception {
        var start=new CyclicBarrier(3);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<3;i++) futures.add(pool.submit(()-> {start.await();try {
                register.execute(new RegisterBuyerAccount.Input("Ana",LocalDate.of(2000,1,1),"race@example.com","12345678",PASSWORD,PASSWORD,true,true));return true;
            } catch(com.transformersas.marketplace.shared.error.BusinessException e) {assertThat(e.code()).isEqualTo("ACCOUNT_ACCESS_REQUIRED");return false;} }));
            int created=0;for(var f:futures) if(f.get(60,TimeUnit.SECONDS)) created++;
            assertThat(created).isEqualTo(1);
        }
        assertThat(count("account_profiles")).isEqualTo(1);
        long id=createAccount("role@example.com","VENDEDOR");
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<EnableBuyerRole.Result>>();
            for(int i=0;i<4;i++) futures.add(pool.submit(()->enable.execute(id,true,true)));
            int added=0;for(var f:futures) if(f.get(30,TimeUnit.SECONDS).buyerRoleAdded()) added++;
            assertThat(added).isEqualTo(1);
        }
        assertThat(jdbc.queryForList("SELECT role FROM user_account_roles WHERE account_id=?",String.class,id)).containsExactlyInAnyOrder("VENDEDOR","COMPRADOR");
    }
    @Test void inactiveAndPendingAccountsCannotEnableAndAlreadyBuyerNeedsNoNewConsent() {
        long id=createAccount("pending@example.com","VENDEDOR");
        jdbc.update("UPDATE user_accounts SET email_verified_at=NULL WHERE id=?",id);
        assertThatThrownBy(()->enable.execute(id,true,true)).isInstanceOf(com.transformersas.marketplace.shared.error.BusinessException.class);
        jdbc.update("UPDATE user_accounts SET email_verified_at=NOW(),status='INACTIVA' WHERE id=?",id);
        assertThatThrownBy(()->enable.execute(id,true,true)).isInstanceOf(com.transformersas.marketplace.shared.error.BusinessException.class);
        long buyer=createAccount("buyer@example.com","COMPRADOR");assertThat(enable.execute(buyer,true,true).buyerRoleAdded()).isFalse();
        assertThat(count("buyer_consents")).isZero();
    }
    @Test void providerTimeoutKeepsPendingRegistrationRecoverable() throws Exception {
        doAnswer(call -> { Thread.sleep(20000); return null; }).when(notifier).notifyVerification(anyString(),anyString());
        long started=System.nanoTime();
        publicPost("/api/buyers/register",form()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.verificationDeliveryFailed").value(true));
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofSeconds(13));
        assertThat(count("account_profiles")).isEqualTo(1);
        mail();resend().andExpect(status().isAccepted());confirm(tokens.get("new@example.com")).andExpect(status().isNoContent());
    }
    @Test void enablingWhileCredentialsChangePreservesTheNewPasswordAndOtherRoles() throws Exception {
        long id=createAccount("changing@example.com","VENDEDOR");
        String newHash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("ChangedPassword123!");
        var manager=applicationTransactions();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var password=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,id);
                jdbc.update("UPDATE user_accounts SET password_hash=? WHERE id=?",newHash,id);
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var role=pool.submit(()->enable.execute(id,true,true));
            release.countDown();password.get(15,TimeUnit.SECONDS);assertThat(role.get(15,TimeUnit.SECONDS).buyerRoleAdded()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?",String.class,id)).isEqualTo(newHash);
        assertThat(jdbc.queryForList("SELECT role FROM user_account_roles WHERE account_id=?",String.class,id)).containsExactlyInAnyOrder("COMPRADOR","VENDEDOR");
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private org.springframework.transaction.PlatformTransactionManager applicationTransactions() { return transactions; }

}
