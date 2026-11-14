package com.transformersas.marketplace.address;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class AddressMigrationTests extends AbstractIntegrationTest {
    @Test void upgradeAssignsOnlyOneDefaultWithoutAssigningOrphanAddresses() {
        String name="cu06_address_upgrade";
        String rootUrl=MYSQL.getJdbcUrl().replace("/"+MYSQL.getDatabaseName(),"/");
        var root=new JdbcTemplate(new DriverManagerDataSource(rootUrl,"root",MYSQL.getPassword()));
        root.execute("CREATE DATABASE "+name);
        try {
            String url=MYSQL.getJdbcUrl().replace("/"+MYSQL.getDatabaseName(),"/"+name);
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"root",MYSQL.getPassword()));
            Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).locations("classpath:db/migration").target("47").load().migrate();
            jdbc.update("INSERT INTO user_accounts(email,password_hash,status) VALUES('legacy-address@example.test',?,'ACTIVA')",
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("migration-test-only"));
            long account=jdbc.queryForObject("SELECT id FROM user_accounts WHERE email='legacy-address@example.test'",Long.class);
            for(Long owner:new Long[]{account,account,null})jdbc.update("INSERT INTO addresses(account_id,recipient_name,street,city,department,phone) VALUES(?,'Anterior','Calle histórica','Bogotá','Cundinamarca','3001234567')",owner);
            Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE is_default=TRUE",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT id FROM addresses WHERE is_default=TRUE",Long.class)).isEqualTo(jdbc.queryForObject("SELECT MIN(id) FROM addresses WHERE account_id=?",Long.class,account));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE account_id IS NULL AND is_default=TRUE",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE street='Calle histórica' AND deleted=FALSE",Long.class)).isEqualTo(3);
            assertThatThrownBy(()->jdbc.update("UPDATE addresses SET is_default=TRUE WHERE account_id=?",account)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {root.execute("DROP DATABASE "+name);}
    }
}
