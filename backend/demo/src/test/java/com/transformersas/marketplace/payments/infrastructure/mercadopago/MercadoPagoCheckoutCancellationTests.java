package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MercadoPagoCheckoutCancellationTests extends MercadoPagoH05TestSupport {
    @Autowired MercadoPagoFinancialService financial;
    @Autowired MercadoPagoRecoveryService recovery;
    @Autowired PlatformTransactionManager transactions;

    private void closed(String id) {
        assertThat(checkout(id).get("cancelled_at")).isNotNull();
        assertThat(checkouts.status(accountId,id).status()).isEqualTo("CANCELLED");
        assertThat(checkouts.status(accountId,id).checkoutUrl()).isNull();
        assertThat(count("orders")).isZero(); assertThat(stock()).isEqualTo(10);
    }
    @ParameterizedTest @ValueSource(strings={"STARTING","PENDING","REJECTED","REVIEW","REFUNDED"})
    void closePreOrderStatesWithoutExternalCallsAndRepeatWithoutChanges(String state) {
        var a=start(); jdbc.update("UPDATE payment_checkouts SET status=? WHERE id=?",state,a.id());
        clearInvocations(provider);
        var first=checkouts.cancel(accountId,a.id()); var second=checkouts.cancel(accountId,a.id());
        assertThat(second).isEqualTo(first); closed(a.id());
        assertThat(first.financialStatus()).isEqualTo("REQUIRES_VERIFICATION");
        assertThat(jdbc.queryForList("SELECT status FROM inventory_reservations",String.class)).containsExactly("RELEASED");
        verifyNoInteractions(provider);
    }
    @Test void pendingPaymentIsPreservedAndNewStartDoesNotReuseClosedAttempt() throws Exception {
        var a=start(); payment(a,"456","pending"); assertThat(browser(a.id(),"456")).isEqualTo(200);
        checkouts.cancel(accountId,a.id()); closed(a.id());
        assertThat(count("external_payment_observations")).isEqualTo(1);
        var next=start(); assertThat(next.id()).isNotEqualTo(a.id());
        assertThat(jdbc.queryForList("SELECT status FROM inventory_reservations ORDER BY id",String.class)).containsExactly("RELEASED","ACTIVE");
    }
    @Test void committedApprovalWinsAndMustUseOrdinaryOrderCancellation() throws Exception {
        var a=start(); payment(a,"456","approved"); assertThat(webhook("456")).isEqualTo(200);
        assertThatThrownBy(()->checkouts.cancel(accountId,a.id())).isInstanceOfSatisfying(ResponseStatusException.class,
            e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertOnePurchase(a.id()); assertThat(checkout(a.id()).get("cancelled_at")).isNull();
    }
    @Test void approvalAlreadyAtProviderButUnobservedCannotCreateOrderAfterClose() throws Exception {
        var a=start(); payment(a,"456","approved"); checkouts.cancel(accountId,a.id());
        var results=together(()->webhook("456"),()->browser(a.id(),"456"));
        assertThat(results).allSatisfy(r->{assertThat(r.failure()).isNull();assertThat(r.value()).isEqualTo(200);});
        closed(a.id());assertThat(count("external_payment_observations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT verification_count FROM external_payment_observations WHERE payment_id='456'",Integer.class)).isEqualTo(2);
    }
    @Test void concurrentClosuresAreIdempotent() throws Exception {
        var a=start(); var results=together(()->checkouts.cancel(accountId,a.id()),()->checkouts.cancel(accountId,a.id()));
        assertThat(results).allSatisfy(r->assertThat(r.failure()).isNull());
        assertThat(results.get(0).value()).isEqualTo(results.get(1).value()); closed(a.id());
    }
    @Test void approvalAndClosureRaceHasExactlyOneWinner() throws Exception {
        var a=start(); payment(a,"456","approved");
        var results=together(()->checkouts.cancel(accountId,a.id()),()->checkouts.reconcile(accountId,a.id(),"456"));
        assertThat(results.get(1).failure()).isNull();
        if(checkout(a.id()).get("cancelled_at")!=null) {closed(a.id());assertThat(results.get(0).failure()).isNull();}
        else {assertOnePurchase(a.id());assertThat(results.get(0).failure()).isInstanceOf(ResponseStatusException.class);}
    }
    @ParameterizedTest @ValueSource(strings={"ACTIVE","EXPIRED","RELEASED","CONFIRMED"})
    void onlyActiveReservationsAreReleasedAndStockNeverIncreases(String state) {
        var a=start();jdbc.update("UPDATE inventory_reservations SET status=?",state);
        checkouts.cancel(accountId,a.id());closed(a.id());
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations",String.class)).isEqualTo(state.equals("ACTIVE")?"RELEASED":state);
    }
    @Test void expiredActiveReservationIsReleasedWithoutPhysicalRestock() {
        var a=start();expire();checkouts.cancel(accountId,a.id());closed(a.id());
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations",String.class)).isEqualTo("RELEASED");
    }
    @Test void rollbackRestoresBothCancellationMarkerAndReservations() {
        var a=start();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{checkouts.cancel(accountId,a.id());tx.setRollbackOnly();});
        assertThat(checkout(a.id()).get("cancelled_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations",String.class)).isEqualTo("ACTIVE");
        assertThat(stock()).isEqualTo(10);
    }
    @Test void ownerRoleSessionAndCsrfAreEnforced() throws Exception {
        var a=start();String uri="/api/payments/mercadopago/checkouts/"+a.id()+"/cancellation";
        var other=sessionWithRole("other-close@example.test","COMPRADOR");
        perform(other,post(uri)).andExpect(status().isNotFound());
        var seller=sessionWithRole("seller-close@example.test","VENDEDOR");
        perform(seller,post(uri)).andExpect(status().isForbidden());
        mvc.perform(post(uri).cookie(buyer.cookie())).andExpect(status().isForbidden());
        mvc.perform(post(uri).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isUnauthorized());
        perform(buyer,post("/api/payments/mercadopago/checkouts/00000000-0000-0000-0000-000000000000/cancellation")).andExpect(status().isNotFound());
        assertThat(checkout(a.id()).get("cancelled_at")).isNull();
        perform(buyer,post(uri)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.financialStatus").value("REQUIRES_VERIFICATION")).andExpect(jsonPath("$.cancelledAt").isNotEmpty());
    }
    @Test void getAndRefreshExposePersistedClosureAndFinancialStateOnlyToOwner() throws Exception {
        var a=start();checkouts.cancel(accountId,a.id());payment(a,"456","approved");
        assertThat(browser(a.id(),"456")).isEqualTo(200);
        perform(buyer,get("/api/payments/mercadopago/checkouts/{id}",a.id())).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelledAt").isNotEmpty())
            .andExpect(jsonPath("$.financialStatus").value("PENDING_RESOLUTION")).andExpect(jsonPath("$.checkoutUrl").isEmpty());
        var other=sessionWithRole("other-read-close@example.test","COMPRADOR");
        perform(other,get("/api/payments/mercadopago/checkouts/{id}",a.id())).andExpect(status().isNotFound());
        perform(buyer,post("/api/payments/mercadopago/checkouts/{id}/refresh",a.id()).contentType("application/json").content("{\"paymentId\":\"456\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cancelledAt").isNotEmpty()).andExpect(jsonPath("$.financialStatus").value("PENDING_RESOLUTION"));
        closed(a.id());
    }
    @Test void databaseFailureDuringMarkerUpdateRollsBackReservationRelease() {
        var a=start();
        jdbc.execute("ALTER TABLE payment_checkouts ADD CONSTRAINT test_close_failure CHECK (cancelled_at IS NULL)");
        try {
            assertThatThrownBy(()->checkouts.cancel(accountId,a.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(checkout(a.id()).get("cancelled_at")).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations",String.class)).isEqualTo("ACTIVE");
            assertThat(stock()).isEqualTo(10);
        } finally { jdbc.execute("ALTER TABLE payment_checkouts DROP CHECK test_close_failure"); }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void firstCheckoutTransactionToCommitWins(boolean cancellationFirst) throws Exception {
        var a=start();payment(a,"456","approved");
        var firstReady=new java.util.concurrent.CountDownLatch(1);
        var secondReady=new java.util.concurrent.CountDownLatch(1);
        var results=together(()->new TransactionTemplate(transactions).execute(tx->{
            Object result=cancellationFirst?checkouts.cancel(accountId,a.id()):checkouts.reconcile(accountId,a.id(),"456");
            firstReady.countDown();
            try { assertThat(secondReady.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch(InterruptedException e){throw new AssertionError(e);}
            return result;
        }),()->{
            assertThat(firstReady.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();secondReady.countDown();
            return cancellationFirst?checkouts.reconcile(accountId,a.id(),"456"):checkouts.cancel(accountId,a.id());
        });
        assertThat(results.get(0).failure()).isNull();
        if(cancellationFirst){assertThat(results.get(1).failure()).isNull();closed(a.id());}
        else {assertThat(results.get(1).failure()).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));assertOnePurchase(a.id());}
    }
    @Test void reviewRetainsObservedApprovalAndFinancialNeedAfterCancellation() throws Exception {
        var a=start();expire();payment(a,"456","approved");assertThat(webhook("456")).isEqualTo(200);
        var result=checkouts.cancel(accountId,a.id());closed(a.id());
        assertThat(result.financialStatus()).isEqualTo("PENDING_RESOLUTION");
        assertThat(checkout(a.id())).containsEntry("status","REVIEW");
        assertThat(count("external_payment_observations")).isEqualTo(1);
    }
    @Test void mismatchCannotBecomeAnAutomaticRefundThroughClosingCheckout() throws Exception {
        var a=start();payment(a,"456","approved");
        var data=new HashMap<String,Object>(json.convertValue(payments.get("456"),Map.class));
        data.put("transaction_amount",a.total().add(java.math.BigDecimal.ONE));payments.put("456",json.valueToTree(data));
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(checkouts.cancel(accountId,a.id()).financialStatus()).isEqualTo("REQUIRES_REVIEW");
        financial.recoverBatch();assertThat(count("external_payment_refunds")).isZero();
        verify(provider,never()).requestFullRefund(anyString(),anyString());closed(a.id());
    }
    @Test void freshRecoveryDiscoversAllLatePaymentsWithoutOrdersAndRefundsEachOnceDespiteTimeout() throws Exception {
        var a=start();checkouts.cancel(accountId,a.id());payment(a,"456","approved");payment(a,"457","approved");
        when(provider.searchPayments(eq(a.id()),anyInt())).thenReturn(new MercadoPagoClient.PaymentSearch(List.of("456","457"),false));
        // Fresh worker uses only durable checkout and provider data, no browser state.
        var fresh=new MercadoPagoRecoveryService(jdbc,provider,checkouts,
            new MercadoPagoPaymentJournal(jdbc),transactions,8,10);
        fresh.recoverBatch();closed(a.id());assertThat(count("external_payment_observations")).isEqualTo(2);
        var posts=new AtomicInteger(); var returned=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        when(provider.refunds(anyString())).thenReturn(List.of());
        when(provider.requestFullRefund(anyString(),anyString())).thenAnswer(call->{
            posts.incrementAndGet();returned.add(call.getArgument(0));
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"timeout simulado");
        });
        financial.recoverBatch();assertThat(posts.get()).isEqualTo(2);
        assertThat(count("external_payment_refunds")).isEqualTo(2);
        when(provider.refunds(anyString())).thenAnswer(call->{String id=call.getArgument(0);return returned.contains(id)?List.of(json.valueToTree(Map.of("id","900"+id,"payment_id",id,"amount",a.total(),"status","approved"))):List.of();});
        jdbc.update("UPDATE external_payment_refunds SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
        financial.recoverBatch();assertThat(posts.get()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT status FROM external_payment_refunds",String.class)).containsOnly("CONFIRMED");
        assertThat(webhook("456")).isEqualTo(200);closed(a.id());
        assertThat(posts.get()).isEqualTo(2);assertThat(count("refunds")).isZero();
        assertThat(checkouts.cancel(accountId,a.id()).financialStatus()).isEqualTo("REFUNDED");
    }
}
