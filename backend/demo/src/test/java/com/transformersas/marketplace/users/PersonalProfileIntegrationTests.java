package com.transformersas.marketplace.users;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.users.application.port.EmailChangeNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Pruebas exclusivas de CU-09 con MySQL desechable y correo simulado. */
@org.springframework.test.context.TestPropertySource(properties="logging.level.org.springframework.jdbc.core=INFO")
class PersonalProfileIntegrationTests extends AbstractIntegrationTest {
    @MockitoBean EmailChangeNotifier mail;
    private static final String EMAIL="perfil@cu09.test";
    private Session account() throws Exception {
        long id=createAccount(EMAIL,"COMPRADOR","VENDEDOR");
        jdbc.update("INSERT INTO account_profiles(account_id,full_name,birth_date,phone) VALUES (?,'Ana Perfil','2000-01-01','3001234567')",id);
        return login(EMAIL);
    }
    private String body(String email,String password,long version,boolean enabled) {
        return """
            {"fullName":"Ana Perfil","birthDate":"2000-01-01","email":"%s","phone":"3001234567",
             "promotions":%s,"marketplaceNews":%s,"currentPassword":"%s","version":%d,"confirmChanges":true}
            """.formatted(email,enabled,enabled,password,version);
    }
    private org.springframework.test.web.servlet.ResultActions save(Session s,String body) throws Exception {
        return perform(s,put("/api/account/profile").contentType("application/json").content(body));
    }
    private String requestEmail(Session s) throws Exception {
        var token=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(i->{token.set(i.getArgument(1));return null;}).when(mail).send(anyString(),anyString());
        save(s,body("nuevo@cu09.test",PASSWORD,0,false)).andExpect(status().isOk());
        return token.get();
    }
    @Test void consultaPerfilUnicoDeCuentaMultirrol() throws Exception {
        var s=account();perform(s,get("/api/account/profile")).andExpect(status().isOk())
            .andExpect(jsonPath("$.fullName").value("Ana Perfil")).andExpect(jsonPath("$.essentialCommunications").value(true));
        assertEquals(1,count("account_profiles"));
    }
    @Test void preferenciasNoRequierenContrasenaYEsencialesPermanecen() throws Exception {
        var s=account();save(s,body(EMAIL,"",0,true)).andExpect(status().isOk()).andExpect(jsonPath("$.profile.promotions").value(true));
        save(s,body(EMAIL,"",1,false)).andExpect(status().isOk()).andExpect(jsonPath("$.profile.marketplaceNews").value(false))
            .andExpect(jsonPath("$.profile.essentialCommunications").value(true));
    }
    @Test void datosValidosSeGuardanConContrasena() throws Exception {
        var s=account();save(s,body(EMAIL,PASSWORD,0,false).replace("Ana Perfil","Ana Actualizada"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.profile.fullName").value("Ana Actualizada"));
    }
    @Test void fechaFuturaNoModificaDatos() throws Exception {
        var s=account();save(s,body(EMAIL,PASSWORD,0,false).replace("2000-01-01","2999-01-01")).andExpect(status().isBadRequest());
        assertEquals("2000-01-01",jdbc.queryForObject("SELECT birth_date FROM account_profiles",String.class));
    }
    @Test void datosInvalidosSeRechazan() throws Exception {
        var s=account();save(s,body(EMAIL,PASSWORD,0,false).replace("3001234567","x")).andExpect(status().isBadRequest());
    }
    @Test void anonimoNoAccede() throws Exception {mvc.perform(get("/api/account/profile")).andExpect(status().isUnauthorized());}
    @Test void clienteNoPuedeSeleccionarOtraCuenta() throws Exception {
        var s=account();createAccount("otra@cu09.test","COMPRADOR");
        save(s,body(EMAIL,PASSWORD,0,false).replace("{","{\"accountId\":999,")) .andExpect(status().isBadRequest());
    }
    @Test void csrfEsObligatorio() throws Exception {
        var s=account();mvc.perform(put("/api/account/profile").cookie(s.cookie()).contentType("application/json").content(body(EMAIL,PASSWORD,0,false))).andExpect(status().isForbidden());
    }
    @Test void contrasenaIncorrectaNoAplicaCambios() throws Exception {
        var s=account();save(s,body("nuevo@cu09.test","incorrecta",0,true)).andExpect(status().isForbidden());
        assertEquals(EMAIL,jdbc.queryForObject("SELECT email FROM user_accounts",String.class));assertEquals(0,count("account_profile_settings"));
    }
    @Test void correoOcupadoEsRechazado() throws Exception {
        var s=account();createAccount("ocupado@cu09.test","COMPRADOR");save(s,body("ocupado@cu09.test",PASSWORD,0,false)).andExpect(status().isConflict());
    }
    @Test void correoPendienteConservaDireccionYAlmacenaHash() throws Exception {
        var s=account();String token=requestEmail(s);assertNotNull(token);
        assertEquals(EMAIL,jdbc.queryForObject("SELECT email FROM user_accounts",String.class));
        String hash=jdbc.queryForObject("SELECT token_hash FROM account_email_changes",String.class);
        assertEquals(64,hash.length());assertNotEquals(token,hash);
    }
    @Test void confirmarCorreoRevocaTodasLasSesiones() throws Exception {
        var s=account();var other=login(EMAIL);String token=requestEmail(s);
        perform(s,post("/api/account/profile/email/confirm").contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isNoContent());
        assertEquals("nuevo@cu09.test",jdbc.queryForObject("SELECT email FROM user_accounts",String.class));
        perform(other,get("/api/account/profile")).andExpect(status().isUnauthorized());
        var renewed=login("nuevo@cu09.test");
        perform(renewed,post("/api/account/profile/email/confirm").contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isBadRequest());
    }
    @Test void tokenVencidoNoCambiaCorreo() throws Exception {
        var s=account();String token=requestEmail(s);jdbc.update("UPDATE account_email_changes SET expires_at=NOW()-INTERVAL 1 MINUTE");
        perform(s,post("/api/account/profile/email/confirm").contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isBadRequest());
    }
    @Test void reenvioSustituyeTokenAnterior() throws Exception {
        var s=account();String token=requestEmail(s);
        perform(s,post("/api/account/profile/email/resend").contentType("application/json").content("{\"currentPassword\":\""+PASSWORD+"\"}")).andExpect(status().isOk());
        perform(s,post("/api/account/profile/email/confirm").contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isBadRequest());
        verify(mail,times(2)).send(anyString(),anyString());
    }
    @Test void falloCorreoDejaEstadoRecuperable() throws Exception {
        var s=account();doThrow(new IllegalStateException("Simulación SMTP")).when(mail).send(anyString(),anyString());
        save(s,body("nuevo@cu09.test",PASSWORD,0,false)).andExpect(status().isOk()).andExpect(jsonPath("$.verificationDeliveryFailed").value(true));
        assertEquals(EMAIL,jdbc.queryForObject("SELECT email FROM user_accounts",String.class));assertEquals(1,count("account_email_changes"));
    }
    @Test void versionObsoletaNoSobrescribePerfil() throws Exception {
        var s=account();save(s,body(EMAIL,"",0,true)).andExpect(status().isOk());save(s,body(EMAIL,"",0,false)).andExpect(status().isConflict());
        assertTrue(jdbc.queryForObject("SELECT promotions FROM account_profile_settings",Boolean.class));
    }
    @Test void cierreExigeConfirmacionYContrasena() throws Exception {
        var s=account();perform(s,post("/api/account/profile/closure").contentType("application/json")
            .content("{\"currentPassword\":\"incorrecta\",\"version\":0,\"confirmClosure\":true}")).andExpect(status().isForbidden());
        assertEquals("ACTIVA",jdbc.queryForObject("SELECT status FROM user_accounts",String.class));
    }
    @Test void cierrePermitidoConservaCuentaYPerfilYRevocaSesiones() throws Exception {
        var s=account();var other=login(EMAIL);
        perform(s,get("/api/account/profile/closure")).andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
        perform(s,post("/api/account/profile/closure").contentType("application/json")
            .content("{\"currentPassword\":\""+PASSWORD+"\",\"version\":0,\"confirmClosure\":true}")).andExpect(status().isNoContent());
        assertEquals("INACTIVA",jdbc.queryForObject("SELECT status FROM user_accounts",String.class));assertEquals(1,count("account_profiles"));
        perform(other,get("/api/account/profile")).andExpect(status().isUnauthorized());
        var csrf=mvc.perform(get("/api/auth/csrf")).andReturn();var data=json.readTree(csrf.getResponse().getContentAsString());
        mvc.perform(post("/api/auth/login").cookie(csrf.getResponse().getCookie("SESSION"))
            .param("email",EMAIL).param("password",PASSWORD).header(data.get("headerName").asString(),data.get("token").asString())).andExpect(status().isUnauthorized());
    }
    @Test void pedidoPendienteDeVendedorImpideCierre() throws Exception {
        var s=account();assignStoreOwner(1,accountIdOf(EMAIL));long product=seedProduct(1,"Producto",3,"100");seedOrder(1,"CONFIRMED",product,1,"100");
        perform(s,get("/api/account/profile/closure")).andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
        perform(s,post("/api/account/profile/closure").contentType("application/json")
            .content("{\"currentPassword\":\""+PASSWORD+"\",\"version\":0,\"confirmClosure\":true}")).andExpect(status().isConflict());
    }

    @org.springframework.beans.factory.annotation.Autowired com.transformersas.marketplace.users.application.usecase.ManagePersonalProfile profiles;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.beans.factory.annotation.Autowired com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase notifications;
    @org.junit.jupiter.api.AfterEach void limpiarFixturesPropios() {jdbc.update("DELETE FROM return_requests");}
    private long ownOrder(String state,boolean seller) {
        long id=accountIdOf(EMAIL);if(seller)assignStoreOwner(1,id);
        long product=seedProduct(1,"Producto histórico",5,"100");long order=seedOrder(1,state,product,1,"100");
        jdbc.update("UPDATE orders SET account_id=? WHERE id=?",id,order);return order;
    }
    @Test void pedidoPendienteCompradorImpideCierre() throws Exception {
        var s=account();ownOrder("CONFIRMED",false);
        perform(s,get("/api/account/profile/closure")).andExpect(status().isOk()).andExpect(jsonPath("$.blockers[0].code").value("ORDERS"));
    }
    @Test void reclamacionPendienteImpideCierre() throws Exception {
        var s=account();long order=ownOrder("DELIVERED",false);
        jdbc.update("""
            INSERT INTO claims(order_id,product_id,product_name,item_total,buyer_account_id,store_id,description,status,created_at,updated_at)
            SELECT ?,product_id,'Producto',100,?,1,'Reclamación de prueba','OPEN',NOW(),NOW() FROM order_items WHERE order_id=?
            """,order,accountIdOf(EMAIL),order);
        perform(s,get("/api/account/profile/closure")).andExpect(status().isOk()).andExpect(jsonPath("$.blockers[0].code").value("CLAIMS"));
    }
    @Test void devolucionPendienteImpideCierre() throws Exception {
        var s=account();long order=ownOrder("DELIVERED",true);
        jdbc.update("""
            INSERT INTO return_requests(order_id,order_item_id,product_id,product_name,quantity,unit_price,refund_amount,buyer_account_id,store_id,status,reason_code,description,created_at,updated_at)
            SELECT order_id,id,product_id,product_name,quantity,unit_price,100,?,1,'REQUESTED','OTHER','Devolución de prueba',NOW(),NOW() FROM order_items WHERE order_id=?
            """,accountIdOf(EMAIL),order);
        perform(s,get("/api/account/profile/closure")).andExpect(status().isOk()).andExpect(jsonPath("$.blockers[0].code").value("RETURNS"));
    }
    @Test void cierreConHistoriaNoBorraPedidosYRetiraOfertas() throws Exception {
        var s=account();long order=ownOrder("DELIVERED",true);
        jdbc.update("INSERT INTO refunds(order_id,amount,idempotency_key,status,correlation_id,created_at,updated_at) VALUES (?,100,'cu09-historia','COMPLETED','cu09',NOW(),NOW())",order);
        jdbc.update("""
            INSERT INTO claims(order_id,product_id,product_name,item_total,buyer_account_id,store_id,description,status,created_at,updated_at)
            SELECT ?,product_id,'Producto',100,?,1,'Reclamación finalizada','RESOLVED',NOW(),NOW() FROM order_items WHERE order_id=?
            """,order,accountIdOf(EMAIL),order);
        jdbc.update("""
            INSERT INTO return_requests(order_id,order_item_id,product_id,product_name,quantity,unit_price,refund_amount,buyer_account_id,store_id,status,reason_code,description,created_at,updated_at)
            SELECT order_id,id,product_id,product_name,quantity,unit_price,100,?,1,'FINISHED','OTHER','Devolución finalizada',NOW(),NOW() FROM order_items WHERE order_id=?
            """,accountIdOf(EMAIL),order);
        perform(s,post("/api/account/profile/closure").contentType("application/json")
            .content("{\"currentPassword\":\""+PASSWORD+"\",\"version\":0,\"confirmClosure\":true}")).andExpect(status().isNoContent());
        assertEquals(1,count("orders"));assertEquals(1,count("order_items"));assertEquals(1,count("order_status_history"));
        assertEquals(1,count("refunds"));assertEquals(1,count("claims"));assertEquals(1,count("return_requests"));
        assertEquals("SUSPENDED",jdbc.queryForObject("SELECT status FROM stores WHERE id=1",String.class));
        assertFalse(jdbc.queryForObject("SELECT active FROM products",Boolean.class));
    }
    @Test void tokenAjenoNoPuedeConfirmarse() throws Exception {
        var s=account();String token=requestEmail(s);createAccount("ajena@cu09.test","COMPRADOR");var other=login("ajena@cu09.test");
        perform(other,post("/api/account/profile/email/confirm").contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isBadRequest());
    }
    @Test void tokenInvalidoNoCambiaCorreo() throws Exception {
        var s=account();requestEmail(s);
        perform(s,post("/api/account/profile/email/confirm").contentType("application/json").content("{\"token\":\"incorrecto\"}")).andExpect(status().isBadRequest());
    }
    @Test void cambioContrasenaInvalidaConfirmacionPendiente() throws Exception {
        var s=account();String token=requestEmail(s);
        jdbc.update("UPDATE user_accounts SET password_hash=? WHERE email=?",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("OtraClave123!"),EMAIL);
        perform(s,post("/api/account/profile/email/confirm").contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isBadRequest());
    }
    @Test void cuentasLegadasNoSeDuplicanAlConsultar() throws Exception {
        createAccount(EMAIL,"VENDEDOR");var s=login(EMAIL);
        perform(s,get("/api/account/profile")).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(EMAIL));assertEquals(0,count("account_profiles"));
    }
    @Test void preferenciasNoBloqueanNotificacionEsencialReal() throws Exception {
        var s=account();save(s,body(EMAIL,"",0,false)).andExpect(status().isOk());
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->notifications.execute(
            new com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand(
                com.transformersas.marketplace.notifications.domain.model.RecipientType.BUYER,accountIdOf(EMAIL),"SECURITY","Seguridad","Aviso esencial","ACCOUNT","1","cu09-esencial")));
        assertEquals(1,count("notifications"));
    }
    @Test void actualizacionesSimultaneasSoloAplicanUnaVersion() throws Exception {
        account();long id=accountIdOf(EMAIL);var ready=new java.util.concurrent.CyclicBarrier(2);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<String> save=()->{ready.await();try {
                profiles.update(id,new com.transformersas.marketplace.users.application.usecase.ManagePersonalProfile.Update("Ana Perfil",java.time.LocalDate.of(2000,1,1),EMAIL,"3001234567",true,true,"",0));return "OK";
            }catch(com.transformersas.marketplace.shared.error.BusinessException e){return e.code();}};
            var first=executor.submit(save);var second=executor.submit(save);
            assertEquals(java.util.Set.of("OK","PROFILE_CHANGED"),java.util.Set.of(first.get(),second.get()));
        }
        assertEquals(1L,jdbc.queryForObject("SELECT version FROM account_profile_settings",Long.class));
    }
    @Test void errorPersistenciaRevierteDatosYPreferencias() throws Exception {
        var s=account();jdbc.execute("ALTER TABLE account_profile_settings ADD CONSTRAINT cu09_fallo CHECK (promotions=FALSE)");
        try {save(s,body(EMAIL,PASSWORD,0,true).replace("Ana Perfil","Nombre no persistido")).andExpect(status().isServiceUnavailable());}
        finally {jdbc.execute("ALTER TABLE account_profile_settings DROP CHECK cu09_fallo");}
        assertEquals("Ana Perfil",jdbc.queryForObject("SELECT full_name FROM account_profiles",String.class));assertEquals(0,count("account_profile_settings"));
    }
    @Test void timeoutCorreoEsAcotadoYRecuperable() throws Exception {
        var s=account();doAnswer(i->{Thread.sleep(30_000);return null;}).when(mail).send(anyString(),anyString());
        long start=System.nanoTime();save(s,body("nuevo@cu09.test",PASSWORD,0,false)).andExpect(status().isOk()).andExpect(jsonPath("$.verificationDeliveryFailed").value(true));
        assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()<12_000);
        assertEquals(EMAIL,jdbc.queryForObject("SELECT email FROM user_accounts",String.class));
    }

    @org.springframework.beans.factory.annotation.Autowired com.transformersas.marketplace.users.domain.repository.UserAccountRepository accounts;
    @Test void guardadoAntiguoNoReactivaCuentaCerrada() throws Exception {
        account();long id=accountIdOf(EMAIL);var stale=accounts.findById(id).orElseThrow();
        profiles.close(id,PASSWORD,0);
        var error=assertThrows(com.transformersas.marketplace.shared.error.BusinessException.class,()->accounts.save(stale));
        assertEquals("ACCOUNT_CHANGED",error.code());assertEquals("INACTIVA",jdbc.queryForObject("SELECT status FROM user_accounts",String.class));
    }
    @Test void guardadoAntiguoNoRevierteCorreoConfirmado() throws Exception {
        var s=account();long id=accountIdOf(EMAIL);var stale=accounts.findById(id).orElseThrow();String token=requestEmail(s);
        profiles.confirm(id,token);
        assertThrows(com.transformersas.marketplace.shared.error.BusinessException.class,()->accounts.save(stale));
        assertEquals("nuevo@cu09.test",jdbc.queryForObject("SELECT email FROM user_accounts",String.class));
    }

    @Test void confirmacionesConcurrentesNoAsignanUnCorreoADosCuentas() throws Exception {
        account();long first=accountIdOf(EMAIL);long second=createAccount("segunda@cu09.test","COMPRADOR");
        var tokens=new java.util.concurrent.ConcurrentLinkedQueue<String>();doAnswer(inv->{tokens.add(inv.getArgument(1));return null;}).when(mail).send(anyString(),anyString());
        profiles.update(first,new com.transformersas.marketplace.users.application.usecase.ManagePersonalProfile.Update("Ana Perfil",java.time.LocalDate.of(2000,1,1),"comun@cu09.test","3001234567",false,false,PASSWORD,0));
        String a=tokens.remove();
        profiles.update(second,new com.transformersas.marketplace.users.application.usecase.ManagePersonalProfile.Update("Segunda Persona",java.time.LocalDate.of(2000,1,1),"comun@cu09.test","3001234567",false,false,PASSWORD,0));
        String b=tokens.remove();var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var one=pool.submit(()->{barrier.await();try{profiles.confirm(first,a);return "OK";}catch(com.transformersas.marketplace.shared.error.BusinessException e){return e.code();}});
            var two=pool.submit(()->{barrier.await();try{profiles.confirm(second,b);return "OK";}catch(com.transformersas.marketplace.shared.error.BusinessException e){return e.code();}});
            assertEquals(java.util.Set.of("OK","EMAIL_ALREADY_REGISTERED"),java.util.Set.of(one.get(),two.get()));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE email='comun@cu09.test'",Integer.class));
    }
}
