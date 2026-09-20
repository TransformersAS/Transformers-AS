package com.transformersas.marketplace.shared.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;

/** Rechaza propiedades desconocidas solo en los requests marcados con StrictJsonRequest. */
@Configuration(proxyBeanMethods = false)
public class StrictJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictJsonRequestCustomizer() {
        return builder -> builder.addHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                                 ValueDeserializer<?> deserializer, Object beanOrClass,
                                                 String propertyName) {
                Class<?> target = beanOrClass instanceof Class<?> type ? type : beanOrClass.getClass();
                if (StrictJsonRequest.class.isAssignableFrom(target)) {
                    ctxt.reportInputMismatch(target, "Propiedad no permitida: %s", propertyName);
                }
                return false;
            }
        });
    }
}
