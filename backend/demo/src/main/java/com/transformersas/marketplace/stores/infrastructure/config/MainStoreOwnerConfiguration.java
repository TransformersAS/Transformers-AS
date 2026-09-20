package com.transformersas.marketplace.stores.infrastructure.config;

import com.transformersas.marketplace.stores.application.usecase.AssignMainStoreOwnerUseCase;
import com.transformersas.marketplace.stores.application.usecase.AssignMainStoreOwnerUseCase.Outcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Al arrancar, asigna la tienda principal a la cuenta de stores.main-store-owner-email (variable
 * MAIN_STORE_OWNER_EMAIL), que por defecto está vacía y entonces no hace nada. Un problema se avisa en el log pero
 * nunca impide el arranque. El correo no se escribe en el log: es un dato personal.
 */
@Configuration(proxyBeanMethods = false)
public class MainStoreOwnerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MainStoreOwnerConfiguration.class);

    @Bean
    ApplicationRunner assignMainStoreOwner(AssignMainStoreOwnerUseCase assignMainStoreOwner,
                                           @Value("${stores.main-store-owner-email:}") String ownerEmail) {
        return args -> {
            try {
                report(assignMainStoreOwner.execute(ownerEmail));
            } catch (RuntimeException failure) {
                log.warn("No se pudo asignar la dueña de la tienda principal ({}); el arranque continúa",
                        failure.getClass().getSimpleName());
            }
        };
    }

    private static void report(Outcome outcome) {
        switch (outcome) {
            case NOT_CONFIGURED -> log.debug("MAIN_STORE_OWNER_EMAIL no está definida: la tienda principal no se toca");
            case ASSIGNED -> log.info("La tienda principal quedó asignada a la cuenta de MAIN_STORE_OWNER_EMAIL");
            case ALREADY_HAS_OWNER -> log.info("La tienda principal ya tiene dueña; MAIN_STORE_OWNER_EMAIL no cambia nada");
            case STORE_NOT_FOUND -> log.warn("MAIN_STORE_OWNER_EMAIL está definida pero la tienda principal no existe");
            case ACCOUNT_NOT_FOUND -> log.warn(
                    "MAIN_STORE_OWNER_EMAIL no corresponde a ninguna cuenta; la tienda principal sigue sin dueña");
            case ACCOUNT_NOT_SELLER -> log.warn(
                    "La cuenta de MAIN_STORE_OWNER_EMAIL no tiene el rol VENDEDOR; la tienda principal sigue sin dueña");
            case ACCOUNT_OWNS_ANOTHER_STORE -> log.warn(
                    "La cuenta de MAIN_STORE_OWNER_EMAIL ya es dueña de otra tienda; la tienda principal sigue sin dueña");
        }
    }
}
