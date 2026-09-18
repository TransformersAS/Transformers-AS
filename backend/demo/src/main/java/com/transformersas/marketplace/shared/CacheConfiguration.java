package com.transformersas.marketplace.shared;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/** Enables cache infrastructure only; no business caches or policies exist yet. */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration {
}
