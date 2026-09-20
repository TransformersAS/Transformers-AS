package com.transformersas.marketplace.stores.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registra la configuración del módulo de tiendas. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorePolicyProperties.class)
public class StoresConfiguration {
}
