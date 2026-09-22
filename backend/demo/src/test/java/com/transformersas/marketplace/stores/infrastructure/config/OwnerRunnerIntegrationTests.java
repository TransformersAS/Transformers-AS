package com.transformersas.marketplace.stores.infrastructure.config;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties="stores.main-store-owner-email=owner-runner@example.test")
class OwnerRunnerIntegrationTests extends AbstractIntegrationTest {
    @Autowired @Qualifier("assignMainStoreOwner") ApplicationRunner runner;
    void run() throws Exception { runner.run(new DefaultApplicationArguments(new String[0])); }
    Long owner() { return jdbc.queryForObject("SELECT owner_account_id FROM stores WHERE id=1",Long.class); }
    @Test void runnerHandlesMissingAccountBuyerSellerAndIdempotentRestart() throws Exception {
        run(); assertThat(owner()).isNull();
        long id=createAccount("owner-runner@example.test","COMPRADOR");
        run(); assertThat(owner()).isNull();
        jdbc.update("INSERT INTO user_account_roles(account_id,role) VALUES (?,'VENDEDOR')",id);
        run(); assertThat(owner()).isEqualTo(id);
        run(); assertThat(owner()).isEqualTo(id);
    }
    @Test void existingOtherStoreIsNotStolenByStartup() throws Exception {
        long id=createAccount("owner-runner@example.test","VENDEDOR");
        seedStore(2,"Existing"); assignStoreOwner(2,id);
        run(); assertThat(owner()).isNull();
        assertThat(jdbc.queryForObject("SELECT owner_account_id FROM stores WHERE id=2",Long.class)).isEqualTo(id);
    }
    @Test void missingStoreAndDatabaseFailureDoNotAbortStartup() throws Exception {
        jdbc.update("DELETE FROM store_shipping_methods");
        jdbc.update("DELETE FROM stores WHERE id=1");
        try { assertThatCode(this::run).doesNotThrowAnyException(); }
        finally { seedStore(1,"Tienda principal"); }
        jdbc.execute("RENAME TABLE stores TO stores_unavailable");
        try { assertThatCode(this::run).doesNotThrowAnyException(); }
        finally { jdbc.execute("RENAME TABLE stores_unavailable TO stores"); }
        assertThat(owner()).isNull();
    }
}
