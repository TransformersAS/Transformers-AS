package com.transformersas.marketplace.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Lee o genera X-Correlation-Id, lo publica en el MDC y lo devuelve en la respuesta. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = CorrelationContext.sanitize(request.getHeader(CorrelationContext.HEADER));
        if (id == null) {
            id = CorrelationContext.newId();
        }
        MDC.put(CorrelationContext.MDC_KEY, id);
        // La cabecera se fija antes de la cadena para que también salga en respuestas de error.
        response.setHeader(CorrelationContext.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationContext.MDC_KEY);
        }
    }
}
