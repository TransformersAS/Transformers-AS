package com.transformersas.marketplace.messaging;

import com.transformersas.marketplace.messaging.application.ConversationService;
import com.transformersas.marketplace.messaging.application.ConversationService.*;
import com.transformersas.marketplace.messaging.domain.ConversationRepository;
import com.transformersas.marketplace.notifications.domain.repository.ExternalNotificationGateway;
import com.transformersas.marketplace.notifications.domain.model.ExternalNotificationUnavailableException;
import com.transformersas.marketplace.reports.application.ReportSubmissionService;
import com.transformersas.marketplace.reports.application.dto.SubmitReportRequest;
import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.users.domain.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.mysql.MySQLContainer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** Isolated CU10 database, real Flyway/MySQL/transactions/session security, no production providers. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "notifications.queue.enabled=false", "marketplace.demo-data.enabled=false", "app.email-verification.from=cu10@example.test"})
@AutoConfigureMockMvc
class Cu10IntegrationTests {
    @ServiceConnection static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("cu10_test").withCommand("--log-bin-trust-function-creators=1");
    static { MYSQL.start(); }
    @Autowired ConversationService service;
    @Autowired ConversationRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ReportSubmissionService reports;
    @Autowired com.transformersas.marketplace.reports.application.ContentModerationStateService moderation;
    @Autowired com.transformersas.marketplace.reports.application.ContentSnapshotService snapshots;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean ExternalNotificationGateway external;
    @LocalServerPort int port;
    long buyer,seller,stranger,store,product,otherProduct,order;
    Actor b,s;
    static final String PASSWORD="Cu10TestPassword!";
    static final String HASH=new BCryptPasswordEncoder(4).encode(PASSWORD);
    @BeforeEach void fixtures() {
        buyer=account("COMPRADOR");seller=account("VENDEDOR");stranger=account("COMPRADOR");b=new Actor(buyer,false);s=new Actor(seller,true);
        String name="CU10 store "+UUID.randomUUID();
        jdbc.update("INSERT INTO stores(name,owner_account_id) VALUES(?,?)",name,seller);store=jdbc.queryForObject("SELECT id FROM stores WHERE name=?",Long.class,name);
        product=product(store);otherProduct=product(store);
        jdbc.update("INSERT INTO addresses(recipient_name,street,city,department,phone,account_id) VALUES('CU10','Calle 1','Bogota','Bogota','3000000000',?)",buyer);
        long address=jdbc.queryForObject("SELECT MAX(id) FROM addresses",Long.class);
        jdbc.update("""
            INSERT INTO orders(account_id,store_id,status,total,address_id,shipping_method,transaction_id,created_at,
                delivery_recipient_name,delivery_street,delivery_city,delivery_department,delivery_phone)
            VALUES(?,?,'CONFIRMED',10,?,'STANDARD',?,NOW(6),'CU10','Calle 1','Bogota','Bogota','3000000000')
            """,buyer,store,address,UUID.randomUUID().toString());
        order=jdbc.queryForObject("SELECT MAX(id) FROM orders",Long.class);
        jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES(?,?,'CU10',1,10,10),(?,?,'CU10 second',1,10,10)",order,product,order,otherProduct);
    }
    long account(String role) {
        String email="cu10-"+UUID.randomUUID()+"@example.test";
        jdbc.update("INSERT INTO user_accounts(email,password_hash,status,email_verified_at) VALUES(?,?,'ACTIVA',NOW(6))",email,HASH);
        long id=jdbc.queryForObject("SELECT id FROM user_accounts WHERE email=?",Long.class,email);
        jdbc.update("INSERT INTO user_account_roles VALUES(?,?)",id,role);return id;
    }
    long product(long store) {String name="CU10 product "+UUID.randomUUID();jdbc.update("INSERT INTO products(name,price,stock,category,active,store_id) VALUES(?,10,10,'Hogar',TRUE,?)",name,store);return jdbc.queryForObject("SELECT id FROM products WHERE name=?",Long.class,name);}
    AccountPrincipal principal(long id,Role role) {return new AccountPrincipal(new UserAccount(id,jdbc.queryForObject("SELECT email FROM user_accounts WHERE id=?",String.class,id),HASH,AccountStatus.ACTIVA,Set.of(role)));}
    Input input(String text) {return new Input(text,UUID.randomUUID().toString());}
    byte[] png() throws IOException {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    long thread() {return service.start(b,product,null).id();}

    @Test void productOrderReuseDifferentContextsAndMultiStoreOwnership() {
        long id=thread();assertThat(thread()).isEqualTo(id);
        assertThat(service.start(b,otherProduct,null).id()).isNotEqualTo(id);
        long ordered=service.start(b,product,order).id();assertThat(ordered).isNotEqualTo(id);
        assertThat(service.start(b,product,order).id()).isEqualTo(ordered);
        long seller2=account("VENDEDOR");jdbc.update("INSERT INTO stores(name,owner_account_id) VALUES(?,?)","CU10 "+UUID.randomUUID(),seller2);
        long store2=jdbc.queryForObject("SELECT id FROM stores WHERE owner_account_id=?",Long.class,seller2);
        jdbc.update("UPDATE products SET store_id=? WHERE id=?",store2,otherProduct);
        assertThat(service.start(b,otherProduct,order).sellerId()).isEqualTo(seller2);
        assertThatThrownBy(()->service.start(new Actor(stranger,false),product,order)).hasMessageContaining("no disponible");
        assertThatThrownBy(()->service.start(s,product,null)).hasMessageContaining("comprador");
    }
    @Test void messagesReadArchiveCloseReopenAndIdempotency() throws Exception {
        long id=thread();var draft=input("Hola <script>alert(1)</script>");var first=service.send(b,id,draft,null);
        assertThat(service.send(b,id,draft,null).id()).isEqualTo(first.id());
        assertThatThrownBy(()->service.send(b,id,new Input("changed",draft.clientKey()),null)).hasMessageContaining("clave");
        assertThat(service.get(s,id).unread()).isEqualTo(1);assertThat(service.get(b,id).unread()).isZero();
        service.read(s,id,first.id());assertThat(service.get(s,id).unread()).isZero();
        service.archive(b,id,true);assertThat(service.list(b,false,0)).isEmpty();assertThat(service.list(s,false,0)).hasSize(1);
        service.close(s,id,true);assertThatThrownBy(()->service.send(b,id,input("No"),null)).hasMessageContaining("cerrada");
        service.close(b,id,false);var reply=service.send(s,id,input("Respuesta"),png());
        assertThat(service.get(b,id).archived()).isFalse();assertThat(service.get(b,id).unread()).isEqualTo(1);
        service.archive(b,id,true);service.archive(b,id,false);assertThat(service.list(b,false,0)).hasSize(1);
        assertThat(service.history(b,id,0,Long.MAX_VALUE,10)).extracting("id").containsExactly(first.id(),reply.id());
        assertThat(service.image(b,reply.id()).type()).isEqualTo("image/png");
        assertThat(repository.message(first.id()).orElseThrow().text()).isEqualTo(draft.text());
    }
    @Test void invalidImagesEmptyMessagesRestrictionsAndIsolation() throws Exception {
        long id=thread();
        assertThatThrownBy(()->service.send(b,id,input("  "),null)).hasMessageContaining("válida");
        assertThatThrownBy(()->service.send(b,id,input("draft retained"),"<script>renamed.png</script>".getBytes())).hasMessageContaining("JPG");
        assertThatThrownBy(()->service.send(b,id,input(""),new byte[5242881])).hasMessageContaining("máximo");
        assertThat(service.history(b,id,0,Long.MAX_VALUE,10)).isEmpty();
        assertThatThrownBy(()->service.get(new Actor(stranger,false),id)).hasMessageContaining("no disponible");
        assertThatThrownBy(()->service.get(new Actor(account("VENDEDOR"),true),id)).hasMessageContaining("no disponible");
        jdbc.update("UPDATE stores SET status='RESTRICTED',status_reason='CU10 test' WHERE id=?",store);
        assertThat(service.get(s,id).id()).isEqualTo(id);
        assertThatThrownBy(()->service.send(s,id,input("blocked"),null)).hasMessageContaining("restricción");
        jdbc.update("UPDATE user_accounts SET status='INACTIVA' WHERE id=?",buyer);
        assertThat(service.get(b,id).id()).isEqualTo(id);assertThatThrownBy(()->service.send(b,id,input("blocked"),null)).hasMessageContaining("restricción");
    }
    @Test void communicationRestrictedActiveAccountKeepsRealSessionAndReadAccess() throws Exception {
        long id=thread();service.send(s,id,input("history remains readable"),null);var session=login(buyer);
        jdbc.update("UPDATE user_accounts SET communications_restricted=TRUE WHERE id=?",buyer);
        mvc.perform(session.apply(get("/api/conversations/"+id+"/messages"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("history remains readable")));
        mvc.perform(session.apply(post("/api/conversations/"+id+"/messages")).contentType("application/json")
            .content("{\"text\":\"blocked\",\"clientKey\":\"cu10-restricted-message\"}")).andExpect(status().isForbidden());
        assertThat(service.history(b,id,0,Long.MAX_VALUE,50)).hasSize(1);
        jdbc.update("UPDATE user_accounts SET communications_restricted=FALSE WHERE id=?",buyer);
        assertThat(service.send(b,id,input("restriction lifted"),null).id()).isPositive();
    }
    @Test void concurrentStartAndSendStablePagination() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<Long> start=()->{barrier.await();return thread();};
            var a=pool.submit(start);var c=pool.submit(start);long id=a.get(20,TimeUnit.SECONDS);assertThat(c.get(20,TimeUnit.SECONDS)).isEqualTo(id);
            Callable<Long> send=()->{barrier.await();return service.send(b,id,input("concurrent"),null).id();};
            var one=pool.submit(send);var two=pool.submit(send);long x=one.get(20,TimeUnit.SECONDS),y=two.get(20,TimeUnit.SECONDS);
            assertThat(x).isNotEqualTo(y);
            assertThat(service.history(s,id,0,Long.MAX_VALUE,1)).extracting("id").containsExactly(Math.max(x,y));
            assertThat(service.history(s,id,0,Math.max(x,y),1)).extracting("id").containsExactly(Math.min(x,y));
            assertThat(service.get(s,id).unread()).isEqualTo(2);
            var retry=input("same event");
            Callable<Long> repeated=()->{barrier.await();return service.send(b,id,retry,null).id();};
            var first=pool.submit(repeated);var second=pool.submit(repeated);
            assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo(second.get(20,TimeUnit.SECONDS));
            assertThat(service.history(s,id,0,Long.MAX_VALUE,10)).hasSize(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE reference_type='CONVERSATION' AND reference_id=?",Long.class,Long.toString(id))).isEqualTo(3);
        }
    }
    @Test void atomicNotificationAfterCommitExternalFailureAndPersistenceFailure() {
        long id=thread();doThrow(new ExternalNotificationUnavailableException("CU10 simulated outage",null)).when(external).send(any());
        var sent=service.send(b,id,input("persisted"),null);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT attempts FROM notifications WHERE event_key=?",Integer.class,"conversation-message-"+sent.id())).isGreaterThan(0));
        assertThat(service.history(s,id,0,Long.MAX_VALUE,10)).hasSize(1);
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE reference_type='CONVERSATION' AND reference_id=?",Long.class,Long.toString(id));
        // Test-only trigger injects database failure after the message insert, at notification persistence.
        jdbc.execute("CREATE TRIGGER cu10_test_fail_notification BEFORE INSERT ON notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CU10 injected persistence failure'");
        try {assertThatThrownBy(()->service.send(b,id,input("must rollback"),null)).isInstanceOf(Exception.class);}
        finally {jdbc.execute("DROP TRIGGER cu10_test_fail_notification");}
        assertThat(service.history(s,id,0,Long.MAX_VALUE,10)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE reference_type='CONVERSATION' AND reference_id=?",Long.class,Long.toString(id))).isEqualTo(count);
    }
    record Session(jakarta.servlet.http.Cookie cookie,String header,String token) {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder apply(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {return request.cookie(cookie).header(header,token);}
    }
    Session login(long account) throws Exception {
        var mapper=new tools.jackson.databind.ObjectMapper();
        var anonymous=mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        var token=mapper.readTree(anonymous.getContentAsString());
        String email=jdbc.queryForObject("SELECT email FROM user_accounts WHERE id=?",String.class,account);
        var response=mvc.perform(post("/api/auth/login").cookie(anonymous.getCookie("SESSION"))
            .header(token.get("headerName").asString(),token.get("token").asString()).param("email",email).param("password",PASSWORD))
            .andExpect(status().isNoContent()).andReturn().getResponse();
        var cookie=response.getCookie("SESSION");
        var refreshed=mapper.readTree(mvc.perform(get("/api/auth/csrf").cookie(cookie)).andReturn().getResponse().getContentAsString());
        return new Session(cookie,refreshed.get("headerName").asString(),refreshed.get("token").asString());
    }
    @Test void authenticatedEndpointsCsrfRoleAndAttachmentOwnership() throws Exception {
        long id=thread();var sent=service.send(b,id,input("image"),png());
        var own=login(buyer);var foreign=login(stranger);var admin=login(account("ADMIN"));
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(own.apply(get("/api/conversations"))).andExpect(status().isOk());
        mvc.perform(foreign.apply(get("/api/conversations/"+id))).andExpect(status().isNotFound());
        mvc.perform(foreign.apply(get("/api/conversations/images/"+sent.id()))).andExpect(status().isNotFound());
        mvc.perform(admin.apply(get("/api/conversations"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/conversations/"+id+"/closed").cookie(own.cookie()).contentType("application/json").content("{\"value\":true}")).andExpect(status().isForbidden());
        mvc.perform(own.apply(post("/api/conversations/"+id+"/closed")).contentType("application/json").content("{\"value\":true}")).andExpect(status().isOk());
        mvc.perform(own.apply(put("/api/conversations/"+id+"/messages/"+sent.id()))).andExpect(status().is4xxClientError());
        mvc.perform(own.apply(delete("/api/conversations/"+id+"/messages/"+sent.id()))).andExpect(status().is4xxClientError());
    }
    @Test void browserWithRealBackendMysqlAndSimulatedExternalProvider() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(!Files.isDirectory(root.resolve("frontend"))) root=root.getParent();
        var process=new ProcessBuilder("npx","playwright","test","--config=playwright.cu10.config.ts");
        process.directory(root.resolve("frontend").toFile());
        var env=process.environment();env.put("CU10_BACKEND_PORT",Integer.toString(port));env.put("CU10_PASSWORD",PASSWORD);
        env.put("CU10_BUYER",jdbc.queryForObject("SELECT email FROM user_accounts WHERE id=?",String.class,buyer));
        env.put("CU10_SELLER",jdbc.queryForObject("SELECT email FROM user_accounts WHERE id=?",String.class,seller));
        env.put("CU10_PRODUCT",Long.toString(product));env.put("CU10_OTHER_PRODUCT",Long.toString(otherProduct));env.put("CU10_ORDER",Long.toString(order));
        env.put("CU10_PRODUCT_NAME",jdbc.queryForObject("SELECT name FROM products WHERE id=?",String.class,product));
        Path log=root.resolve("frontend/test-results-cu10-run.log");process.redirectErrorStream(true).redirectOutput(log.toFile());
        doThrow(new ExternalNotificationUnavailableException("CU10 browser simulated outage",null)).when(external).send(any());
        jdbc.execute("""
            CREATE TRIGGER cu10_browser_fail_notification BEFORE INSERT ON notifications FOR EACH ROW
            BEGIN
              IF NEW.reference_type='CONVERSATION' AND EXISTS(
                SELECT 1 FROM conversation_messages WHERE CONCAT('conversation-message-',id)=NEW.event_key AND text='CU10 fallo persistencia')
              THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CU10 browser injected persistence failure'; END IF;
            END
            """);
        try {
            var running=process.start();boolean ended=running.waitFor(180,TimeUnit.SECONDS);
            if(!ended) {running.descendants().forEach(ProcessHandle::destroy);running.destroyForcibly();}
            assertThat(ended).as("Playwright timeout; log %s",log).isTrue();
            assertThat(running.exitValue()).as("Playwright log: %s",Files.readString(log)).isZero();
        } finally {jdbc.execute("DROP TRIGGER cu10_browser_fail_notification");}
    }
    @Test void moderationPreservesMessageAndAttachmentForCu20Support() throws Exception {
        long id=thread();var sent=service.send(s,id,input("original evidence"),png());
        var auth=UsernamePasswordAuthenticationToken.authenticated(principal(buyer,Role.COMPRADOR),null,principal(buyer,Role.COMPRADOR).getAuthorities());
        var result=reports.submit(auth,new SubmitReportRequest("MENSAJE",Long.toString(sent.id()),"SPAM","CU10 evidencia de spam"),List.of());
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->moderation.apply(
            com.transformersas.marketplace.reports.domain.model.ReportContentType.MENSAJE,Long.toString(sent.id()),
            com.transformersas.marketplace.reports.domain.model.ModerationDecision.OCULTAR_TEMPORALMENTE,result.caseId()));
        var view=service.history(b,id,0,Long.MAX_VALUE,10).getFirst();
        assertThat(view.text()).contains("oculto");assertThat(view.image()).isFalse();
        assertThatThrownBy(()->service.image(b,sent.id())).hasMessageContaining("no disponible");
        assertThat(snapshots.find(com.transformersas.marketplace.reports.domain.model.ReportContentType.MENSAJE,Long.toString(sent.id())).orElseThrow().text()).isEqualTo("original evidence");
        var support=login(account("SOPORTE"));var buyerSession=login(buyer);
        mvc.perform(support.apply(get("/api/support/messaging/images/"+sent.id()))).andExpect(status().isOk()).andExpect(content().bytes(png()));
        mvc.perform(buyerSession.apply(get("/api/support/messaging/images/"+sent.id()))).andExpect(status().isForbidden());
        var unreported=service.send(s,id,input("private unreported"),png());
        mvc.perform(support.apply(get("/api/support/messaging/images/"+unreported.id()))).andExpect(status().isNotFound());
    }
    @Test void reportUsesCu20PreservesOriginalAndBlocksOwnOrForeignContent() {
        long id=thread();var sent=service.send(s,id,input("CU10 evidence original"),null);
        var auth=UsernamePasswordAuthenticationToken.authenticated(principal(buyer,Role.COMPRADOR),null,principal(buyer,Role.COMPRADOR).getAuthorities());
        String reason="CONTENIDO_INAPROPIADO";
        var request=new SubmitReportRequest("MENSAJE",Long.toString(sent.id()),reason,"CU10 comportamiento inapropiado");
        var response=reports.submit(auth,request,List.of());assertThat(response.id()).isPositive();
        assertThat(repository.message(sent.id()).orElseThrow().text()).isEqualTo("CU10 evidence original");
        var ownAuth=UsernamePasswordAuthenticationToken.authenticated(principal(seller,Role.VENDEDOR),null,principal(seller,Role.VENDEDOR).getAuthorities());
        assertThatThrownBy(()->reports.submit(ownAuth,request,List.of())).hasMessageContaining("tuyo");
        var foreignAuth=UsernamePasswordAuthenticationToken.authenticated(principal(stranger,Role.COMPRADOR),null,principal(stranger,Role.COMPRADOR).getAuthorities());
        assertThatThrownBy(()->reports.submit(foreignAuth,request,List.of())).hasMessageContaining("disponible");
    }
}
