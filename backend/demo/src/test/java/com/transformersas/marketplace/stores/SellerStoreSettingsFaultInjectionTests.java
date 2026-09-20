package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A10: un error al guardar no deja la tienda a medias. */
class SellerStoreSettingsFaultInjectionTests extends AbstractIntegrationTest {

    @MockitoSpyBean AuditRecorder audit;

    private static final String BODY =
            "{\"name\":\"Nombre que no debe quedar\",\"returnWindowDays\":60,\"version\":0}";

    private Session seller;

    @BeforeEach
    void setUp() throws Exception {
        seller = sellerOfStore("seller@example.com", 1);
    }

    @Test
    void a10_ifTheAuditFailsAfterSavingEverythingIsRolledBack() throws Exception {
        doThrow(new IllegalStateException("auditoría caída")).when(audit).record(eq(ActorType.SELLER), any(),
                eq("STORE_SETTINGS_UPDATED"), eq("STORE"), any(), eq(AuditOutcome.SUCCESS), any(Map.class));

        assertThatThrownBy(() -> perform(seller, put("/api/seller/store").contentType("application/json")
                .content(BODY))).hasRootCauseMessage("auditoría caída");

        Map<String, Object> row = jdbc.queryForMap("SELECT name, return_window_days, version FROM stores WHERE id = 1");
        assertThat(row).containsEntry("name", "Tienda principal").containsEntry("return_window_days", 30)
                .containsEntry("version", 0L);
        assertThat(count("audit_events")).isZero();

        reset(audit);
        perform(seller, put("/api/seller/store").contentType("application/json").content(BODY))
                .andExpect(status().isOk());
        assertThat(count("audit_events")).isEqualTo(1);
    }
}
