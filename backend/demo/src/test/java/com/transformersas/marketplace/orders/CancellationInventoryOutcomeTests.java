package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H10: pedidos históricos y productos reales en MySQL temporal, sin proveedores reales. */
class CancellationInventoryOutcomeTests extends AbstractIntegrationTest {
    @Autowired SimulatedRefundGateway refunds;
    @Autowired SimulatedLogisticsGateway logistics;
    private Session buyer, seller;
    private long product, order;
    @BeforeEach void seed() throws Exception {
        refunds.reset(); logistics.reset();
        buyer=sessionWithRole("inventory-buyer@example.test","COMPRADOR");
        seller=sellerOfStore("inventory-seller@example.test",1);
        product=seedProduct(1,"Lámpara histórica",5,"100");
        order=seedOrder(1,"CONFIRMED",product,2,"100");
        jdbc.update("UPDATE orders SET account_id=? WHERE id=?",accountIdOf("inventory-buyer@example.test"),order);
    }
    private int cancel() throws Exception {
        return perform(buyer,post("/api/orders/{id}/cancellation",order).contentType("application/json")
            .content("{\"reasonCode\":\"CHANGED_MIND\"}")).andReturn().getResponse().getStatus();
    }
    private int stock(long id) { return jdbc.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,id); }
    private JsonNode audit() throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT details FROM audit_events WHERE action='ORDER_CANCELLED' AND entity_id=?",String.class,order));
    }
    private String message() {
        return jdbc.queryForObject("SELECT message FROM notifications WHERE event_key=?",String.class,"order-"+order+"-CANCELLED-STORE");
    }
    private void exactlyOnce() {
        assertThat(count("order_cancellations")).isEqualTo(1);assertThat(count("refunds")).isEqualTo(1);
        assertThat(refunds.requestCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='ORDER_CANCELLED'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,order)).isEqualTo("CANCELLED");
    }
    @ParameterizedTest @ValueSource(strings={"ACTIVE","PAUSED","RETIRED"})
    void restoresExistingProductWithoutChangingPublication(String state) throws Exception {
        jdbc.update("UPDATE products SET status=?,active=? WHERE id=?",state,state.equals("ACTIVE"),product);
        assertThat(cancel()).isEqualTo(200);assertThat(stock(product)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT status FROM products WHERE id=?",String.class,product)).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id=?",Boolean.class,product)).isEqualTo(state.equals("ACTIVE"));
        assertThat(audit().path("restoredUnits").path(Long.toString(product)).asInt()).isEqualTo(2);
        assertThat(audit().path("skippedUnits").size()).isZero();
        assertThat(message()).contains("Reposición completa","2 unidades").doesNotContain("parcial");
        exactlyOnce();assertThat(cancel()).isEqualTo(409);assertThat(stock(product)).isEqualTo(7);exactlyOnce();
        perform(seller,get("/api/seller/notifications")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].message").value(message()));
    }
    private long secondLine() {
        long other=seedProduct(1,"Mesa",10,"50");
        jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES (?,?,'Mesa',3,50,150)",order,other);
        jdbc.update("UPDATE orders SET total=350 WHERE id=?",order);
        return other;
    }
    @Test void missingLineAllowsPartialRestorationAndRecordsExactMissingQuantity() throws Exception {
        long missing=secondLine();jdbc.update("DELETE FROM products WHERE id=?",missing);
        assertThat(cancel()).isEqualTo(200);assertThat(stock(product)).isEqualTo(7);exactlyOnce();
        var details=audit();assertThat(details.path("restoredUnits").path(Long.toString(product)).asInt()).isEqualTo(2);
        assertThat(details.path("skippedProducts").get(0).asLong()).isEqualTo(missing);
        assertThat(details.path("skippedUnits").path(Long.toString(missing)).asInt()).isEqualTo(3);
        assertThat(message()).contains("Reposición parcial","2 unidades","Lámpara histórica: 2","3 unidades").doesNotContain("sus unidades volvieron");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id=?",Integer.class,missing)).isZero();
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id=?",java.math.BigDecimal.class,order)).isEqualByComparingTo("350");
        assertThat(cancel()).isEqualTo(409);assertThat(stock(product)).isEqualTo(7);exactlyOnce();
    }
    @Test void allMissingNeverRecreatesOrCreditsAnotherProduct() throws Exception {
        long other=secondLine(), unrelated=seedProduct(1,"Sin relación",17,"1");
        jdbc.update("DELETE FROM products WHERE id IN (?,?)",product,other);
        assertThat(cancel()).isEqualTo(200);exactlyOnce();
        assertThat(count("products")).isEqualTo(1);assertThat(stock(unrelated)).isEqualTo(17);
        assertThat(audit().path("restoredUnits").size()).isZero();
        assertThat(audit().path("skippedUnits").path(Long.toString(product)).asInt()).isEqualTo(2);
        assertThat(audit().path("skippedUnits").path(Long.toString(other)).asInt()).isEqualTo(3);
        assertThat(message()).contains("No fue posible recuperar 5 unidades","ya no existen").doesNotContain("Reposición completa");
        assertThat(cancel()).isEqualTo(409);exactlyOnce();
    }
    @Test void repeatedHistoricalLinesAggregateQuantitiesForMissingProduct() throws Exception {
        jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES (?,?,'Lámpara',3,100,300)",order,product);
        jdbc.update("UPDATE orders SET total=500 WHERE id=?",order);
        jdbc.update("DELETE FROM products WHERE id=?",product);
        assertThat(cancel()).isEqualTo(200);
        assertThat(audit().path("skippedUnits").path(Long.toString(product)).asInt()).isEqualTo(5);
        assertThat(message()).contains("recuperar 5 unidades");exactlyOnce();
    }
    @Test void largeMissingOrderKeepsEveryQuantityInSmallAuditEvents() throws Exception {
        jdbc.update("DELETE FROM products WHERE id=?",product);
        for(int i=0;i<100;i++) jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES (?,?,'Ausente',3,1,3)",order,900000L+i);
        jdbc.update("UPDATE orders SET total=500 WHERE id=?",order);
        assertThat(cancel()).isEqualTo(200);exactlyOnce();
        var omissions=jdbc.queryForList("SELECT details FROM audit_events WHERE action='ORDER_STOCK_NOT_RESTORED' AND entity_id=?",String.class,order);
        assertThat(omissions).hasSize(101);
        long units=0;
        for(String data:omissions) {
            var row=json.readTree(data);units+=row.path("quantity").asInt();
            assertThat(row.path("reason").asString()).isEqualTo("PRODUCT_NOT_FOUND");
            assertThat(data.length()).isLessThan(2000);
        }
        assertThat(units).isEqualTo(302);assertThat(message()).contains("recuperar 302 unidades");
        assertThat(cancel()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='ORDER_STOCK_NOT_RESTORED'",Integer.class)).isEqualTo(101);
    }

    @Test void partialNoticeIsBoundedEvenWithManyLongHistoricalNames() throws Exception {
        long missing=secondLine();jdbc.update("DELETE FROM products WHERE id=?",missing);
        String longName="Lámpara 🙂\n".repeat(20);
        jdbc.update("UPDATE order_items SET product_name=? WHERE order_id=? AND product_id=?",longName,order,product);
        for(int i=0;i<4;i++) {
            long extra=seedProduct(1,"Producto "+i,10,"1");
            jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES (?,?,?,1,1,1)",order,extra,longName);
        }
        jdbc.update("UPDATE orders SET total=354 WHERE id=?",order);
        assertThat(cancel()).isEqualTo(200);exactlyOnce();
        assertThat(message()).contains("6 unidades","3 unidades","y otros productos").doesNotContain("\n");
        assertThat(message().length()).isLessThanOrEqualTo(1000);
        assertThat(stock(product)).isEqualTo(7);
    }

    private int[] race(Callable<Integer> first,Callable<Integer> second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try {
            Future<Integer> a=pool.submit(() -> {barrier.await(10,TimeUnit.SECONDS);return first.call();});
            Future<Integer> b=pool.submit(() -> {barrier.await(10,TimeUnit.SECONDS);return second.call();});
            return new int[]{a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS)};
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void simultaneousBuyerCancellationsRestoreAndNotifyOnce() throws Exception {
        assertThat(race(this::cancel,this::cancel)).containsExactlyInAnyOrder(200,409);
        assertThat(stock(product)).isEqualTo(7);exactlyOnce();
    }
    @ParameterizedTest @ValueSource(strings={"start-preparation","ready-for-dispatch"})
    void racingOperationalTransitionNeverDuplicatesStockOrRefund(String action) throws Exception {
        if(action.equals("ready-for-dispatch")) jdbc.update("UPDATE orders SET status='IN_PREPARATION' WHERE id=?",order);
        int[] results=race(this::cancel,() -> perform(seller,post("/api/seller/orders/{id}/"+action,order))
            .andReturn().getResponse().getStatus());
        assertThat(results[0]).isIn(200,409);assertThat(results[1]).isIn(200,409);
        if(action.equals("ready-for-dispatch")) assertThat(results).containsExactlyInAnyOrder(200,409);
        if(results[0]==200) {assertThat(stock(product)).isEqualTo(7);exactlyOnce();}
        else {
            assertThat(results[1]).isEqualTo(200);assertThat(stock(product)).isEqualTo(5);
            assertThat(count("order_cancellations")).isZero();assertThat(count("refunds")).isZero();assertThat(refunds.requestCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='ORDER_CANCELLED'",Integer.class)).isZero();
        }
    }
}
