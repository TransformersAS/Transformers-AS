package com.transformersas.marketplace.product;

import java.math.BigDecimal;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Example;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalDemoProductConfiguration {
    @Bean
    ApplicationRunner provisionLocalDemoProduct(ProductRepository products) {
        return args -> {
            String name = "Camiseta demo local";
            Product probe = new Product();
            probe.setName(name);
            // Buscar también productos desactivados, sin restaurar su stock ni estado.
            probe.setActive(null);
            if (!products.exists(Example.of(probe))) {
                products.save(new Product(null, name,
                        "Producto de desarrollo local para probar compras y pedidos.",
                        new BigDecimal("25000.00"), 100, "Ropa", true));
            }
        };
    }
}
