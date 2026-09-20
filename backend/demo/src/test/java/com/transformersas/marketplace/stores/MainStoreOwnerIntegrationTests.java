package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.stores.application.usecase.AssignMainStoreOwnerUseCase;
import com.transformersas.marketplace.stores.application.usecase.AssignMainStoreOwnerUseCase.Outcome;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** MAIN_STORE_OWNER_EMAIL contra MySQL real: solo la columna de dueña de la tienda 1, y solo si está vacía. */
class MainStoreOwnerIntegrationTests extends AbstractIntegrationTest {

    @Autowired AssignMainStoreOwnerUseCase useCase;
    @Autowired StoreRepository stores;
    @Autowired Environment environment;
    @Autowired ApplicationContext context;

    private Long ownerOfStoreOne() {
        return stores.findById(1L).orElseThrow().ownerAccountId();
    }

    @Test
    void theRunnerIsWiredAndTheDefaultConfigurationDoesNothing() {
        assertThat(context.getBean("assignMainStoreOwner")).isInstanceOf(ApplicationRunner.class);
        assertThat(environment.getProperty("stores.main-store-owner-email")).isEmpty();
        assertThat(useCase.execute(environment.getProperty("stores.main-store-owner-email")))
                .isEqualTo(Outcome.NOT_CONFIGURED);
        assertThat(ownerOfStoreOne()).isNull();
    }

    @Test
    void aSellerAccountBecomesTheOwnerAndNothingElseChanges() {
        long owner = createAccount("dueno@example.com", "VENDEDOR");
        seedProduct(1, "Lámpara", 3, "10.00");
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT name, description, status, version, return_window_days FROM stores WHERE id = 1");
        int products = count("products");

        assertThat(useCase.execute("  Dueno@Example.COM ")).isEqualTo(Outcome.ASSIGNED);

        assertThat(ownerOfStoreOne()).isEqualTo(owner);
        assertThat(jdbc.queryForMap(
                "SELECT name, description, status, version, return_window_days FROM stores WHERE id = 1"))
                .isEqualTo(before);
        assertThat(count("products")).isEqualTo(products);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE owner_account_id IS NOT NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void runningItAgainNeverReplacesTheOwner() {
        long first = createAccount("primero@example.com", "VENDEDOR");
        createAccount("segundo@example.com", "VENDEDOR");
        assertThat(useCase.execute("primero@example.com")).isEqualTo(Outcome.ASSIGNED);

        assertThat(useCase.execute("primero@example.com")).isEqualTo(Outcome.ALREADY_HAS_OWNER);
        assertThat(useCase.execute("segundo@example.com")).isEqualTo(Outcome.ALREADY_HAS_OWNER);

        assertThat(ownerOfStoreOne()).isEqualTo(first);
    }

    @Test
    void anUnknownAccountLeavesTheStoreWithoutOwner() {
        assertThat(useCase.execute("nadie@example.com")).isEqualTo(Outcome.ACCOUNT_NOT_FOUND);

        assertThat(ownerOfStoreOne()).isNull();
    }

    @Test
    void aBuyerOnlyAccountIsRejected() {
        createAccount("comprador@example.com", "COMPRADOR");

        assertThat(useCase.execute("comprador@example.com")).isEqualTo(Outcome.ACCOUNT_NOT_SELLER);

        assertThat(ownerOfStoreOne()).isNull();
    }

    @Test
    void anAccountThatAlreadyOwnsAnotherStoreIsRejected() {
        long owner = createAccount("dueno@example.com", "VENDEDOR");
        seedStore(2, "Otra tienda");
        assignStoreOwner(2, owner);

        assertThat(useCase.execute("dueno@example.com")).isEqualTo(Outcome.ACCOUNT_OWNS_ANOTHER_STORE);

        assertThat(ownerOfStoreOne()).isNull();
        Store other = stores.findById(2L).orElseThrow();
        assertThat(other.ownerAccountId()).isEqualTo(owner);
    }

    @Test
    void aSellerAccountWithSeveralRolesIsAccepted() {
        long owner = createAccount("multirol@example.com", "COMPRADOR", "VENDEDOR");

        assertThat(useCase.execute("multirol@example.com")).isEqualTo(Outcome.ASSIGNED);

        assertThat(ownerOfStoreOne()).isEqualTo(owner);
    }
}
