package com.transformersas.marketplace.inventory;

import com.transformersas.marketplace.inventory.application.usecase.GetStockLevelsUseCase;
import com.transformersas.marketplace.inventory.application.usecase.RestoreStockUseCase;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D10, RNF-015: la reposición de stock es atómica (UPDATE stock = stock + n) y exige una transacción abierta. */
class RestoreStockUseCaseTests extends AbstractIntegrationTest {

    @Autowired RestoreStockUseCase restore;
    @Autowired GetStockLevelsUseCase levels;
    @Autowired TransactionTemplate tx;

    private int stockOf(long product) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product);
    }

    @Test
    void increasesTheStockOfAnExistingProduct() {
        long product = seedProduct(1, "Lámpara", 5, "100.00");

        boolean restored = tx.execute(status -> restore.execute(product, 3));

        assertThat(restored).isTrue();
        assertThat(stockOf(product)).isEqualTo(8);
    }

    @Test
    void requiresAnOpenTransaction() {
        long product = seedProduct(1, "Lámpara", 5, "100.00");

        assertThatThrownBy(() -> restore.execute(product, 3)).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(stockOf(product)).isEqualTo(5);
    }

    @Test
    void aMissingProductIsReportedWithoutFailing() {
        Boolean restored = tx.execute(status -> restore.execute(999_999L, 3));

        assertThat(restored).isFalse();
    }

    @Test
    void nonPositiveQuantitiesAreRejected() {
        long product = seedProduct(1, "Lámpara", 5, "100.00");

        for (int quantity : new int[]{0, -1}) {
            assertThatThrownBy(() -> tx.execute(status -> restore.execute(product, quantity)))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code()).isEqualTo("INVALID_QUANTITY"));
        }
        assertThat(stockOf(product)).isEqualTo(5);
    }

    @Test
    void theIncreaseRollsBackWithTheCallersTransaction() {
        long product = seedProduct(1, "Lámpara", 5, "100.00");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            restore.execute(product, 4);
            throw new IllegalStateException("fallo inyectado");
        })).hasMessage("fallo inyectado");

        assertThat(stockOf(product)).isEqualTo(5);
    }

    @Test
    void concurrentIncreasesNeverLoseUnits() {
        long product = seedProduct(1, "Lámpara", 0, "100.00");
        int workers = 20;
        var start = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return tx.execute(status -> restore.execute(product, 1));
            }));
        }
        start.countDown();
        calls.forEach(CompletableFuture::join);

        assertThat(stockOf(product)).isEqualTo(workers); // leer-modificar-escribir habría perdido actualizaciones
    }

    @Test
    void stockLevelsReturnOnlyExistingProducts() {
        long product = seedProduct(1, "Lámpara", 5, "100.00");

        Map<Long, Integer> result = levels.execute(List.of(product, 999_999L));

        assertThat(result).containsExactly(Map.entry(product, 5));
        assertThat(levels.execute(List.of())).isEmpty();
    }
}
