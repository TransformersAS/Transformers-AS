package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class OrderProvenanceMigrationTests extends AbstractIntegrationTest {
    @Test void realV5ThroughV38PreservesCommercialValuesAndDoesNotInventProvenance() {
        String database="provenance_migration_test";
        String url=MYSQL.getJdbcUrl().replace("/"+MYSQL.getDatabaseName(),"/"+database);
        var admin=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()));
        admin.execute("CREATE DATABASE "+database);
        try {
            var old=new JdbcTemplate(new DriverManagerDataSource(url,"root",MYSQL.getPassword()));
            migrate(url,"5");
            old.update("INSERT INTO addresses(recipient_name,street,city,department,phone) VALUES ('Ana','Original','Cali','Valle','123')");
            old.update("INSERT INTO orders(status,total,address_id,shipping_method,transaction_id,created_at) VALUES ('CONFIRMED',100,1,'STANDARD','legacy-v5','2020-01-01 12:00:00')");
            old.update("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES (1,9,'Producto original',2,50,100)");
            old.update("UPDATE addresses SET street='Modificada antes de V13'");
            migrate(url,"13");
            assertThat(old.queryForMap("SELECT delivery_street,payment_status,store_id FROM orders WHERE id=1"))
                .containsEntry("delivery_street","Modificada antes de V13").containsEntry("payment_status","APPROVED").containsEntry("store_id",1L);
            old.update("""
                INSERT INTO orders(status,total,address_id,shipping_method,transaction_id,created_at,store_id,payment_status,
                  delivery_recipient_name,delivery_street,delivery_city,delivery_department,delivery_phone)
                VALUES ('CONFIRMED',100,1,'STANDARD','between-v13-v34','2021-01-01 12:00:00',1,'APPROVED',
                  'Ana','Capturada entre V13 y V34','Cali','Valle','123')
                """);
            migrate(url,"14");
            assertThat(old.queryForList("SELECT correlation_id FROM order_status_history",String.class)).containsOnly("migration-V14");
            migrate(url,"34");
            assertThat(old.queryForObject("SELECT COUNT(*) FROM orders WHERE discount_amount IS NULL AND shipping_cost IS NULL AND seller_name_snapshot IS NULL",Integer.class)).isEqualTo(2);
            migrate(url,"38");
            assertThat(old.queryForObject("SELECT COUNT(*) FROM orders WHERE delivery_source='UNKNOWN' AND seller_source='UNKNOWN' AND payment_source='UNKNOWN' AND payment_status_at_capture IS NULL",Integer.class)).isEqualTo(2);
            assertThat(old.queryForMap("SELECT total,delivery_street,payment_status FROM orders WHERE id=1"))
                .containsEntry("total",new java.math.BigDecimal("100.00")).containsEntry("delivery_street","Modificada antes de V13").containsEntry("payment_status","APPROVED");
            assertThat(old.queryForObject("SELECT unit_price FROM order_items WHERE order_id=1",java.math.BigDecimal.class)).isEqualByComparingTo("50");
            old.update("UPDATE addresses SET street='Cambio posterior'");
            assertThat(old.queryForObject("SELECT delivery_street FROM orders WHERE id=1",String.class)).isEqualTo("Modificada antes de V13");
        } finally { admin.execute("DROP DATABASE "+database); }
    }
    private void migrate(String url,String version) {
        Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).locations("classpath:db/migration").target(version).load().migrate();
    }
}
