package com.transformersas.marketplace.favorites;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.notifications.infrastructure.gateway.SimulatedExternalNotificationGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FavoritesIntegrationTests extends AbstractIntegrationTest {
    @Autowired FavoriteService favorites;
    @Autowired ProductAlertService alerts;
    @Autowired SimulatedExternalNotificationGateway external;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    long buyer, other, product;
    @BeforeEach void data() {
        external.reset();
        buyer=createAccount("fav@example.test","COMPRADOR"); other=createAccount("other@example.test","COMPRADOR");
        product=seedProduct(1,"Producto seguido",0,"100.00");
        jdbc.update("INSERT INTO product_variants(product_id,position,name,price,stock) VALUES (?,0,'Talla M',100,0)",product);
    }
    @AfterEach void restoreGateway() { external.reset(); }
    ProductAlertService.Input price(String value) { return new ProductAlertService.Input(product,"PRICE",null,new BigDecimal(value)); }
    ProductAlertService.Input availability() { return new ProductAlertService.Input(product,"AVAILABILITY","Talla M",null); }
    String createPrice() { return alerts.create(buyer,price("80")).alert().id(); }
    void lowerPrice() { jdbc.update("UPDATE products SET price=70 WHERE id=?",product); }
    int notices() { return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='PRODUCT_ALERT'",Integer.class); }
    @Test void favoritesArePersistentIdempotentAndIsolatedWithLiveData() {
        favorites.save(buyer,product);favorites.save(buyer,product);
        assertThat(favorites.ids(buyer)).containsExactly(product);
        assertThat(favorites.list(other,0)).isEmpty();
        jdbc.update("UPDATE products SET price=75,stock=4,active=false,status='PAUSED' WHERE id=?",product);
        var saved=favorites.list(buyer,0).getFirst();
        assertThat(saved.price()).isEqualByComparingTo("75");assertThat(saved.stock()).isEqualTo(4);assertThat(saved.active()).isFalse();
        favorites.remove(other,product);assertThat(favorites.ids(buyer)).hasSize(1);
        favorites.remove(buyer,product);assertThat(favorites.ids(buyer)).isEmpty();
    }
    @Test void alreadySatisfiedPriceDoesNotCreateAnAlert() {
        assertThat(alerts.create(buyer,price("100")).alreadySatisfied()).isTrue();
        assertThat(alerts.list(buyer)).isEmpty();assertThat(notices()).isZero();
    }
    @Test void unpublishedDraftCannotBeSavedOrFollowed() {
        jdbc.update("UPDATE products SET status='DRAFT',active=false WHERE id=?",product);
        assertThatThrownBy(()->favorites.save(buyer,product)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->alerts.create(buyer,price("80"))).isInstanceOf(ResponseStatusException.class);
        assertThat(favorites.ids(buyer)).isEmpty();
    }
    @Test void priceChangesPublishExactlyOnceAndExposeProductLink() throws Exception {
        String id=createPrice();alerts.evaluate(id);assertThat(notices()).isZero();
        lowerPrice();alerts.evaluate(id);alerts.evaluate(id);
        assertThat(notices()).isEqualTo(1);assertThat(alerts.list(buyer).getFirst().status()).isEqualTo("TRIGGERED");
        var session=login("fav@example.test");
        perform(session,get("/api/notifications")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].productId").value(product));
    }
    @Test void availabilityFollowsTheNamedVariantNotItsPosition() {
        String id=alerts.create(buyer,availability()).alert().id();
        jdbc.update("UPDATE products SET stock=10 WHERE id=?",product);alerts.evaluate(id);assertThat(notices()).isZero();
        jdbc.update("UPDATE product_variants SET position=1,stock=3 WHERE product_id=?",product);
        jdbc.update("INSERT INTO product_variants(product_id,position,name,price,stock) VALUES (?,0,'Talla S',100,0)",product);
        alerts.evaluate(id);assertThat(notices()).isEqualTo(1);
    }
    @Test void pauseReactivateAndDeleteAreHonored() {
        String id=createPrice();alerts.changeStatus(buyer,id,"PAUSED");lowerPrice();alerts.evaluate(id);assertThat(notices()).isZero();
        assertThatThrownBy(()->alerts.changeStatus(buyer,id,"ACTIVE")).isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE products SET price=100 WHERE id=?",product);alerts.changeStatus(buyer,id,"ACTIVE");
        alerts.changeStatus(buyer,id,"DELETED");lowerPrice();alerts.evaluate(id);assertThat(notices()).isZero();assertThat(alerts.list(buyer)).isEmpty();
    }
    @Test void inactiveAndMissingVariantsNeverNotify() {
        String id=alerts.create(buyer,availability()).alert().id();
        jdbc.update("UPDATE products SET active=false WHERE id=?",product);
        jdbc.update("UPDATE product_variants SET stock=3 WHERE product_id=?",product);alerts.evaluate(id);assertThat(notices()).isZero();
        jdbc.update("UPDATE products SET active=true WHERE id=?",product);
        jdbc.update("DELETE FROM product_variants WHERE product_id=?",product);alerts.evaluate(id);assertThat(notices()).isZero();
    }
    @Test void externalFailureDoesNotLoseInternalNotice() {
        external.setMode(SimulatedExternalNotificationGateway.Mode.UNAVAILABLE);
        String id=createPrice();lowerPrice();alerts.evaluate(id);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT external_status FROM notifications WHERE type='PRODUCT_ALERT'",String.class)).isEqualTo("FAILED"));
        assertThat(notices()).isEqualTo(1);assertThat(alerts.list(buyer).getFirst().status()).isEqualTo("TRIGGERED");
    }
    @Test void editsKeepOwnershipAndHandleAlreadySatisfied() {
        String id=createPrice();
        assertThatThrownBy(()->alerts.edit(other,id,price("90"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->alerts.changeStatus(other,id,"DELETED")).isInstanceOf(ResponseStatusException.class);
        assertThat(alerts.list(other)).isEmpty();
        assertThat(alerts.edit(buyer,id,price("90")).alreadySatisfied()).isFalse();
        assertThat(alerts.edit(buyer,id,price("100")).alreadySatisfied()).isTrue();
        assertThat(alerts.list(buyer).getFirst().status()).isEqualTo("TRIGGERED");assertThat(notices()).isZero();
    }
    @Test void rejectsInvalidOrDuplicateConditions() {
        createPrice();assertThatThrownBy(()->alerts.create(buyer,price("70"))).isInstanceOf(ResponseStatusException.class);
        for (String amount:new String[]{"0","-1","0.001"}) assertThatThrownBy(()->alerts.create(other,price(amount))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->alerts.create(buyer,new ProductAlertService.Input(product,"AVAILABILITY","missing",null))).isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE product_variants SET stock=1 WHERE product_id=?",product);
        assertThatThrownBy(()->alerts.create(buyer,availability())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void concurrentEvaluatorsCannotDuplicateNotice() throws Exception {
        String id=createPrice();lowerPrice();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->alerts.evaluate(id));var second=executor.submit(()->alerts.evaluate(id));
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
        }
        assertThat(notices()).isEqualTo(1);
    }
    @Test void endpointRequiresBuyerSessionAndCsrf() throws Exception {
        mvc.perform(get("/api/favorites")).andExpect(status().isUnauthorized());
        var seller=sessionWithRole("seller-fav@example.test","VENDEDOR");
        perform(seller,get("/api/favorites")).andExpect(status().isForbidden());
        var session=login("fav@example.test");
        mvc.perform(put("/api/favorites/"+product).cookie(session.cookie())).andExpect(status().isForbidden());
        perform(session,put("/api/favorites/"+product)).andExpect(status().isOk());
        perform(session,get("/api/favorites")).andExpect(jsonPath("$[0].id").value(product));
    }
    @Test void availabilityCanBeModifiedAndReactivatedAfterANewOutage() {
        String id=alerts.create(buyer,availability()).alert().id();
        jdbc.update("INSERT INTO product_variants(product_id,position,name,price,stock) VALUES (?,1,'Talla S',100,0)",product);
        alerts.edit(buyer,id,new ProductAlertService.Input(product,"AVAILABILITY","Talla S",null));
        jdbc.update("UPDATE product_variants SET stock=2 WHERE product_id=? AND name='Talla M'",product);
        alerts.evaluate(id);assertThat(notices()).isZero();
        jdbc.update("UPDATE product_variants SET stock=2 WHERE product_id=? AND name='Talla S'",product);
        alerts.evaluate(id);assertThat(notices()).isEqualTo(1);
        jdbc.update("UPDATE product_variants SET stock=0 WHERE product_id=? AND name='Talla S'",product);
        alerts.changeStatus(buyer,id,"ACTIVE");
        jdbc.update("UPDATE product_variants SET stock=1 WHERE product_id=? AND name='Talla S'",product);
        alerts.evaluate(id);alerts.evaluate(id);assertThat(notices()).isEqualTo(2);
    }
    @Test void evaluationRollbackLeavesTheAlertRecoverableWithoutAnExternalNotice() {
        String id=createPrice();lowerPrice();
        assertThatThrownBy(()->tx.executeWithoutResult(status->{alerts.evaluate(id);throw new IllegalStateException("fallo de transacción");})).isInstanceOf(IllegalStateException.class);
        assertThat(notices()).isZero();assertThat(external.requestCount()).isZero();
        assertThat(alerts.list(buyer).getFirst().status()).isEqualTo("ACTIVE");
        alerts.evaluate(id);assertThat(notices()).isEqualTo(1);
    }

    @Test void httpAlertLifecyclePreservesChangesAndDeletion() throws Exception {
        var session=login("fav@example.test");
        perform(session,get("/api/favorites/ids")).andExpect(status().isOk()).andExpect(content().json("[]"));
        var result=perform(session,post("/api/favorites/alerts").contentType("application/json")
                .content(json.writeValueAsString(price("80")))).andExpect(status().isOk()).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).path("alert").path("id").asText();
        perform(session,get("/api/favorites/alerts")).andExpect(jsonPath("$[0].id").value(id));
        perform(session,put("/api/favorites/alerts/"+id).contentType("application/json")
                .content(json.writeValueAsString(price("75")))).andExpect(status().isOk());
        perform(session,patch("/api/favorites/alerts/"+id).contentType("application/json")
                .content("{\"status\":\"PAUSED\"}")).andExpect(status().isOk());
        assertThat(alerts.list(buyer).getFirst().targetPrice()).isEqualByComparingTo("75");
        assertThat(alerts.list(buyer).getFirst().status()).isEqualTo("PAUSED");
        perform(session,delete("/api/favorites/alerts/"+id)).andExpect(status().isOk());
        perform(session,delete("/api/favorites/"+product)).andExpect(status().isOk());
        assertThat(alerts.list(buyer)).isEmpty();
    }

    @Test void missingProductsInvalidPagingAndInvalidLifecycleAreRejected() {
        assertThatThrownBy(()->favorites.save(buyer,Long.MAX_VALUE)).isInstanceOf(ResponseStatusException.class);
        for(int page:new int[]{-1,10001})
            assertThatThrownBy(()->favorites.list(buyer,page)).isInstanceOf(ResponseStatusException.class);
        String id=createPrice();
        assertThatThrownBy(()->alerts.edit(buyer,id,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->alerts.edit(buyer,id,new ProductAlertService.Input(product,"AVAILABILITY","Talla M",null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->alerts.changeStatus(buyer,id,"invalid")).isInstanceOf(ResponseStatusException.class);
        alerts.evaluate("missing-alert");assertThat(notices()).isZero();
        alerts.changeStatus(buyer,id,"PAUSED");
        assertThat(alerts.edit(buyer,id,price("60")).alert().status()).isEqualTo("PAUSED");
    }

    @Test void duplicateAvailabilityCannotReplaceOrReactivateAnotherCondition() {
        String first=alerts.create(buyer,availability()).alert().id();
        jdbc.update("INSERT INTO product_variants(product_id,position,name,price,stock) VALUES (?,1,'Talla S',100,0)",product);
        String second=alerts.create(buyer,new ProductAlertService.Input(product,"AVAILABILITY","Talla S",null)).alert().id();
        assertThatThrownBy(()->alerts.edit(buyer,second,availability())).isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE product_alerts SET status='TRIGGERED' WHERE id=?",second);
        jdbc.update("UPDATE product_alerts SET variant_name='Talla M' WHERE id=?",second);
        assertThatThrownBy(()->alerts.changeStatus(buyer,second,"ACTIVE")).isInstanceOf(ResponseStatusException.class);
        assertThat(alerts.list(buyer).stream().filter(a->a.id().equals(first)).findFirst().orElseThrow().status()).isEqualTo("ACTIVE");
    }

    @Test void alertLimitDoesNotCreateMoreThanFiveHundredRows() {
        jdbc.batchUpdate("INSERT INTO product_alerts(id,account_id,product_id,kind,target_price,status) VALUES (?,?,?,'PRICE',80,'TRIGGERED')",
                java.util.stream.IntStream.range(0,500).mapToObj(i->new Object[]{java.util.UUID.randomUUID().toString(),buyer,product}).toList());
        assertThatThrownBy(()->alerts.create(buyer,price("80"))).isInstanceOf(ResponseStatusException.class);
        assertThat(alerts.list(buyer)).hasSize(500);
    }

    @Test void savedPublicationBecomingPrivateHidesDetailsAndDoesNotTrigger() {
        favorites.save(buyer,product);String id=createPrice();
        jdbc.update("UPDATE products SET status='DRAFT',active=false,price=70 WHERE id=?",product);
        var row=favorites.list(buyer,0).getFirst();
        assertThat(row.name()).isEqualTo("Publicación no disponible");
        assertThat(row.price()).isEqualByComparingTo("0");
        assertThat(row.variants()).isEmpty();assertThat(row.active()).isFalse();
        assertThat(alerts.list(buyer).getFirst().productName()).isEqualTo("Publicación no disponible");
        alerts.evaluate(id);assertThat(notices()).isZero();
    }
}
