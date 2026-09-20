package com.transformersas.marketplace.shared;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** RNF-038: el filtro lee o genera X-Correlation-Id y lo devuelve siempre. */
class CorrelationIdFilterTests extends AbstractIntegrationTest {

    @Autowired org.springframework.core.env.Environment environment;

    private String correlationOf(MvcResult result) {
        return result.getResponse().getHeader("X-Correlation-Id");
    }

    @Test
    void validIncomingIdIsPropagatedToTheResponse() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health").header("X-Correlation-Id", "abc-123_ok.9")).andReturn();
        assertThat(correlationOf(result)).isEqualTo("abc-123_ok.9");
    }

    @Test
    void missingIdIsGenerated() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health")).andReturn();
        assertThat(UUID.fromString(correlationOf(result))).isNotNull();
    }

    @Test
    void unsafeOrOversizedIdsAreReplaced() throws Exception {
        for (String unsafe : new String[]{"con espacios", "a".repeat(65), "x;y", "<script>"}) {
            MvcResult result = mvc.perform(get("/actuator/health").header("X-Correlation-Id", unsafe)).andReturn();
            assertThat(correlationOf(result)).isNotEqualTo(unsafe);
            assertThat(UUID.fromString(correlationOf(result))).isNotNull();
        }
    }

    /** RNF-038: cada línea de log lleva el id de correlación; el patrón conserva el espacio separador final. */
    @Test
    void logPatternIncludesTheCorrelationIdFromTheMdc() {
        assertThat(environment.getProperty("logging.pattern.correlation")).isEqualTo("[%X{correlationId:-}] ");
    }

    @Test
    void idIsAlsoReturnedOnErrorResponses() throws Exception {
        MvcResult result = mvc.perform(get("/api/products").header("X-Correlation-Id", "err-1")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(correlationOf(result)).isEqualTo("err-1");
    }
}
