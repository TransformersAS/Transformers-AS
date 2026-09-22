package com.transformersas.marketplace.users.infrastructure.config;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("local")
class LocalProvisioningIntegrationTests extends AbstractIntegrationTest {
    @Autowired @Qualifier("provisionLocalDemoAccount") ApplicationRunner accounts;
    @Autowired @Qualifier("provisionLocalDemoProduct") ApplicationRunner products;
    @Test void localProvisioningPersistsDataAndDoesNotOverwriteLaterChanges() throws Exception {
        var args=new DefaultApplicationArguments(new String[0]);
        accounts.run(args); products.run(args);
        var id=accountIdOf("demo@marketplace.local");
        assertThat(jdbc.queryForObject("SELECT owner_account_id FROM stores WHERE id=1",Long.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE name='Camiseta demo local'",Integer.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM user_accounts WHERE id=?",Boolean.class,id)).isTrue();
        jdbc.update("UPDATE products SET stock=4,active=false WHERE name='Camiseta demo local'");
        accounts.run(args); products.run(args);
        assertThat(count("products")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE name='Camiseta demo local'",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE name='Camiseta demo local'",Boolean.class)).isFalse();
    }
}
