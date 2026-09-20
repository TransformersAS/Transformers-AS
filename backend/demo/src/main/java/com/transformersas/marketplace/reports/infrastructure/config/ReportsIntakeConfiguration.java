package com.transformersas.marketplace.reports.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Propiedades de la radicación de reportes (CU-20). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReportEvidenceProperties.class)
class ReportsIntakeConfiguration {
}
