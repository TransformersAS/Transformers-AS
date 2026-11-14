package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.domain.model.BuyerOrderFilters;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Medición de SQL Hibernate del repositorio: excluye preparación JDBC, sesión HTTP y consultas de otros módulos. */
@TestPropertySource(properties="spring.jpa.properties.hibernate.generate_statistics=true")
class BuyerOrderQueryMeasurementTests extends AbstractIntegrationTest {
    @Autowired OrderRepository orders;
    @Autowired EntityManagerFactory emf;
    @ParameterizedTest @ValueSource(ints={1,10,50})
    void measuresNewAndLegacyListingWithFreshPersistenceContexts(int size) {
        long account=createAccount("query-measurement@example.test","COMPRADOR");
        long product=seedProduct(1,"Producto de medición",1000,"10");
        for(int i=0;i<size;i++) {
            long id=seedOrder(1,"CONFIRMED",product,1,"10");
            jdbc.update("UPDATE orders SET account_id=?,total=20 WHERE id=?",account,id);
            jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES (?,?,'Segunda línea',1,10,10)",id,product);
        }
        var stats=emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        var unfiltered=orders.findByAccountId(account,BuyerOrderFilters.parse(null,null,null));
        long current=stats.getPrepareStatementCount(),fetches=stats.getCollectionFetchCount();
        assertThat(unfiltered).hasSize(size).allSatisfy(o -> assertThat(o.items()).hasSize(2));
        stats.clear();
        var filtered=orders.findByAccountId(account,BuyerOrderFilters.parse("1000-01-01","9999-12-31","CONFIRMED"));
        long combined=stats.getPrepareStatementCount();
        assertThat(filtered).hasSize(size).allSatisfy(o -> assertThat(o.items()).hasSize(2));
        stats.clear();
        var legacy=orders.findByAccountId(account);
        long previous=stats.getPrepareStatementCount();
        assertThat(legacy).hasSize(size).allSatisfy(o -> assertThat(o.items()).hasSize(2));
        System.out.println("CU11_SQL_COUNT orders="+size+" current="+current+" filtered="+combined+" legacy="+previous+" collectionFetches="+fetches);
        assertThat(current).isPositive();assertThat(combined).isPositive();assertThat(previous).isPositive();
    }
}
