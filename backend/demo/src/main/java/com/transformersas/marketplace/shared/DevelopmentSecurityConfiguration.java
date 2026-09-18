package com.transformersas.marketplace.shared;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORARY development policy, also present in the current demo image.
 * API routes are anonymous. This is NOT authentication or production security.
 * Replace this policy and revisit CSRF when the authentication use case is defined.
 */
@Configuration(proxyBeanMethods = false)
public class DevelopmentSecurityConfiguration {

    @Bean
    SecurityFilterChain developmentSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**").permitAll()
                        .anyRequest().denyAll())
                // Temporary exemption for anonymous development API writes only.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }
}
