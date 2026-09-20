package com.transformersas.marketplace.shared;

import jakarta.servlet.DispatcherType;
import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CookieSerializer sessionCookieSerializer() {
        var serializer = new DefaultCookieSerializer();
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        // Secure follows request.isSecure(), preserving local HTTP and enabling it on HTTPS.
        return serializer;
    }

    @Bean
    DaoAuthenticationProvider accountAuthenticationProvider(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    /**
     * Solo los orígenes configurados pueden llamar a la API desde un navegador, y con credenciales para
     * que viaje la cookie de sesión (por eso no puede ser un comodín).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:4300,http://localhost:4200,http://localhost:8100}")
            List<String> allowedOrigins) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Accept", "X-CSRF-TOKEN", "X-XSRF-TOKEN"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DaoAuthenticationProvider provider,
                                            SecurityContextRepository contexts) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .authenticationProvider(provider)
                .securityContext(context -> context.securityContextRepository(contexts))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/password-recovery/request",
                                "/api/auth/password-recovery/confirm").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/validation/comprador").hasRole("COMPRADOR")
                        .requestMatchers(HttpMethod.GET, "/api/auth/validation/vendedor").hasRole("VENDEDOR")
                        .requestMatchers(HttpMethod.HEAD, "/api/auth/validation/comprador").hasRole("COMPRADOR")
                        .requestMatchers(HttpMethod.HEAD, "/api/auth/validation/vendedor").hasRole("VENDEDOR")
                        .requestMatchers(HttpMethod.GET, "/api/orders", "/api/orders/{id}").hasRole("COMPRADOR")
                        .requestMatchers(HttpMethod.HEAD, "/api/orders", "/api/orders/{id}").hasRole("COMPRADOR")
                        .requestMatchers(HttpMethod.POST, "/api/orders/{id}/cancellation").hasRole("COMPRADOR")
                        // Seguimiento logístico (CU-24/CU-25): el comprador consulta lo suyo; la tienda pasa por
                        // /api/seller/**, donde el rol activo VENDEDOR lo comprueba SessionSellerActorProvider.
                        .requestMatchers(HttpMethod.GET, "/api/orders/{id}/tracking",
                                "/api/returns/{id}/tracking").hasRole("COMPRADOR")
                        .requestMatchers(HttpMethod.POST, "/api/orders/{id}/tracking/refresh",
                                "/api/returns/{id}/tracking/refresh").hasRole("COMPRADOR")
                        // El servicio logístico no tiene sesión: se autentica con la firma HMAC del cuerpo.
                        .requestMatchers(HttpMethod.POST, "/api/logistics/webhooks/**").permitAll()
                        .requestMatchers("/api/support/**").hasRole("SOPORTE")
                        // Administración del catálogo (CU-17): categorías, marcas y atributos.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                // Sin sesión no hay token CSRF que enviar: el webhook se protege con la firma del cuerpo.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/logistics/webhooks/**"))
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
                .formLogin(login -> login
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .successHandler((request, response, authentication) -> {
                            var principal = (AccountPrincipal) authentication.getPrincipal();
                            // Keep only the active role, including when the provider adds factor authorities.
                            var active = UsernamePasswordAuthenticationToken.authenticated(
                                    principal, null, principal.getAuthorities());
                            var context = SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(active);
                            SecurityContextHolder.setContext(context);
                            contexts.saveContext(context, request, response);
                            response.setStatus(204);
                        })
                        .failureHandler((request, response, exception) -> response.setStatus(401)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .build();
    }
}
