package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class MercadoPagoH05ConcurrentReadTests extends MercadoPagoH05TestSupport {
    @Autowired PlatformTransactionManager transactions;

    @Test void lockingReadSeesCommittedCheckoutButConsistentReadRetainsOlderSnapshot() throws Exception {
        var snapshotRead = new CountDownLatch(1);
        var inserted = new CountDownLatch(1);
        var results = together(() -> new TransactionTemplate(transactions).execute(status -> {
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_checkouts", Integer.class)).isZero();
            snapshotRead.countDown();
            try { assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            String id = jdbc.queryForObject("SELECT id FROM payment_checkouts WHERE account_id=? FOR UPDATE", String.class, accountId);
            assertThat(jdbc.queryForList("SELECT id FROM payment_checkouts WHERE id=?", id)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT id FROM payment_checkouts WHERE id=? FOR UPDATE", String.class, id)).isEqualTo(id);
            return id;
        }), () -> {
            assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            try { return start().id(); } finally { inserted.countDown(); }
        });
        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(results.get(0).value()).isEqualTo(results.get(1).value());
    }
}
