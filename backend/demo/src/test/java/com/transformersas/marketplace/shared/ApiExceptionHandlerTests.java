package com.transformersas.marketplace.shared;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTests {
    @RestController
    static class FailingResource {
        @GetMapping("/test/{kind}")
        void get(@PathVariable String kind) {
            switch (kind) {
                case "optimistic" -> throw new ObjectOptimisticLockingFailureException("PrivateEntity", 1L);
                case "pessimistic" -> throw new PessimisticLockingFailureException("private SQL details");
                default -> throw new DataIntegrityViolationException("private constraint details");
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"optimistic", "pessimistic", "integrity"})
    void persistenceFailuresReturnConflictWithoutLeakingDatabaseDetails(String kind) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingResource()).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/test/" + kind)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409)).andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/test/" + kind))
                .andExpect(jsonPath("$.message").value(kind.equals("integrity") ? "La operación viola una restricción de datos"
                        : "Otro usuario modificó el recurso al mismo tiempo; intente de nuevo"));
    }
}
