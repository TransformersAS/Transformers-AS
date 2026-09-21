package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.returns.domain.port.ClaimOpener;
import com.transformersas.marketplace.returns.domain.port.StoreReturnPolicyReader;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Los puertos de devoluciones hacia tiendas (CU-18) y reclamaciones (CU-13), con sus adaptadores reales. */
class ReturnsReadPortsIntegrationTests extends AbstractIntegrationTest {
    @Autowired StoreReturnPolicyReader storePolicy;
    @Autowired ClaimOpener claimOpener;
    @Autowired PlatformTransactionManager transactionManager;

    private long buyerId;
    private long orderId;
    private long productId;

    @BeforeEach
    @AfterEach
    void cleanClaims() {
        List.of("claim_messages", "claim_evidences", "claims").forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    @BeforeEach
    void seed() {
        buyerId = createAccount("comprador@example.com", "COMPRADOR");
        productId = seedProduct(1, "Lámpara", 5, "10.00");
        orderId = seedOrder(1, "DELIVERED", productId, 2, "10.00");
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyerId, orderId);
    }

    @Test
    void theStoreReturnWindowIsTheOneTheStoreConfigured() {
        assertThat(storePolicy.returnWindowDays(1L)).contains(30);

        jdbc.update("UPDATE stores SET return_window_days = 45 WHERE id = 1");

        assertThat(storePolicy.returnWindowDays(1L)).contains(45);
        assertThat(storePolicy.returnWindowDays(987L)).isEmpty();
    }

    @Test
    void aClaimIsOpenedInTheBuyersNameForTheProductOfTheOrder() {
        Long claimId = claimOpener.open(buyerId, orderId, productId, "Llegó roto según el vendedor");

        assertThat(jdbc.queryForMap("SELECT buyer_account_id, store_id, product_id, status, description FROM claims "
                + "WHERE id = ?", claimId)).containsEntry("buyer_account_id", buyerId)
                .containsEntry("store_id", 1L).containsEntry("product_id", productId)
                .containsEntry("status", "OPEN").containsEntry("description", "Llegó roto según el vendedor");
        assertThat(jdbc.queryForObject("SELECT item_total FROM claims WHERE id = ?", java.math.BigDecimal.class,
                claimId)).isEqualByComparingTo("20.00");
    }

    @Test
    void aSecondOpenClaimForTheSameProductIsRefusedByClaims() {
        claimOpener.open(buyerId, orderId, productId, "Primera");

        assertThatThrownBy(() -> claimOpener.open(buyerId, orderId, productId, "Segunda"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(count("claims")).isEqualTo(1);
    }

    @Test
    void theClaimIsOpenedInsideTheCallersTransactionSoAFailureLeavesNothing() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            claimOpener.open(buyerId, orderId, productId, "Se abre y se deshace");
            throw new IllegalStateException("falla después de abrir la reclamación");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("claims")).isZero();
    }
}
