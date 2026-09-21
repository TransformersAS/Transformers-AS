package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.returns.domain.port.ReturnEvidenceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A12: un error técnico al solicitar la devolución no deja ninguna solicitud, imagen, evento, auditoría ni aviso a medias. */
class ReturnRequestAtomicityTests extends ReturnsTestSupport {
    @MockitoSpyBean ReturnEvidenceStorage storage;

    private Session buyer;
    private long buyerId;

    @BeforeEach
    void seed() throws Exception {
        buyer = sessionWithRole("comprador@example.com", "COMPRADOR");
        buyerId = accountIdOf("comprador@example.com");
        Mockito.reset(storage);
    }

    @Test
    void aFailureWhileStoringTheSecondImageRollsBackEverythingAndTheRetryWorks() throws Exception {
        DeliveredOrder order = deliveredOrder(buyerId, LocalDateTime.now().minusDays(3));
        byte[] png = image("png", 4, 4);
        Mockito.doCallRealMethod().doThrow(new IllegalStateException("disco lleno")).when(storage)
                .save(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                        Mockito.any());

        assertThatThrownBy(() -> requestReturnWithImages(buyer, order.orderId(), order.itemId(),
                evidence("1.png", png), evidence("2.png", png))).hasRootCauseMessage("disco lleno");

        assertThat(count("return_requests")).isZero();
        assertThat(count("return_evidence_files")).isZero();
        assertThat(count("return_events")).isZero();
        assertThat(count("notifications")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_type = 'RETURN'", Integer.class))
                .isZero();

        Mockito.reset(storage);
        requestReturnWithImages(buyer, order.orderId(), order.itemId(), evidence("1.png", png), evidence("2.png", png))
                .andExpect(status().isCreated());
        assertThat(count("return_requests")).isEqualTo(1);
        assertThat(count("return_evidence_files")).isEqualTo(2);
        assertThat(count("return_events")).isEqualTo(1);
    }
}
