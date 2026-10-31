package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BuyerOrderReceiptTests extends AbstractIntegrationTest {
    @Autowired SimulatedRefundGateway refunds;
    private Session buyer;
    private long product;

    @BeforeEach void fixtures() throws Exception {
        refunds.reset();
        buyer = sessionWithRole("receipt@example.test", "COMPRADOR");
        product = seedProduct(1, "Producto original", 10, "100.00");
    }
    @AfterEach void resetGateway() { refunds.reset(); }

    private byte[] pdf(long id, boolean download) throws Exception {
        return perform(buyer, get("/api/orders/{id}/receipt", id).param("download", String.valueOf(download)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        (download ? "attachment" : "inline") + "; filename=\"comprobante-pedido-" + id + ".pdf\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
    }
    private String text(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) { return new PDFTextStripper().getText(document); }
    }

    @Test void previewAndDownloadAreValidAndHistoricalChangesDoNotAffectContent() throws Exception {
        long id = checkoutOrder(buyer, product, 2);
        jdbc.update("UPDATE products SET name='Producto cambiado', price=9999 WHERE id=?", product);
        jdbc.update("UPDATE addresses SET street='Dirección cambiada'");
        jdbc.update("UPDATE stores SET name='Tienda cambiada' WHERE id=1");
        for (boolean download : List.of(false, true)) {
            assertThat(text(pdf(id, download))).contains("Pedido #" + id, "Tienda principal", "Producto original",
                    "Precio unitario histórico: $ 100.00", "Cantidad: 2", "Subtotal de productos: $ 200.00",
                    "Descuento aplicado: $ 0.00", "Costo del envío: $ 10000.00", "Total original de la compra: $ 10200.00",
                    "Calle 1 # 2-3", "Estado financiero actual: Aprobado")
                    .doesNotContain("Producto cambiado", "Dirección cambiada", "Tienda cambiada");
        }
        perform(buyer, head("/api/orders/{id}/receipt", id)).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andExpect(content().bytes(new byte[0]));
    }

    @Test void oldOrdersHaveUnknownValuesWhileExplicitZeroRemainsZero() throws Exception {
        long id = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        jdbc.update("UPDATE orders SET account_id=? WHERE id=?", accountIdOf("receipt@example.test"), id);
        assertThat(text(pdf(id, false))).contains("Descuento aplicado: Información histórica no disponible",
                "Costo del envío: Información histórica no disponible", "Vendedor: Información histórica no disponible");
        jdbc.update("UPDATE orders SET discount_amount=0,shipping_cost=0 WHERE id=?", id);
        assertThat(text(pdf(id, true))).contains("Descuento aplicado: $ 0.00", "Costo del envío: $ 0.00");
    }

    @ParameterizedTest @CsvSource({"OK,Reembolsado", "PENDING,Reembolso pendiente"})
    void cancellationPreservesAmountsAndShowsCurrentFinancialStatus(String mode, String label) throws Exception {
        long id = checkoutOrder(buyer, product, 2);
        refunds.setMode(SimulatedRefundGateway.Mode.valueOf(mode));
        perform(buyer, post("/api/orders/{id}/cancellation", id).contentType("application/json")
                .content("{\"reasonCode\":\"CHANGED_MIND\"}")).andExpect(status().isOk());
        assertThat(text(pdf(id, true))).contains("Estado actual del pedido: Cancelado", "Estado financiero actual: " + label,
                "Total original de la compra: $ 10200.00", "Subtotal de productos: $ 200.00", "Costo del envío: $ 10000.00");
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(10);
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test void readsHaveNoBusinessWritesOrPaymentOrInventoryEffects() throws Exception {
        long id = checkoutOrder(buyer, product, 2);
        var tables = List.of("orders", "order_status_history", "refunds", "notifications", "audit_events", "inventory_reservations", "payment_checkouts");
        var counts = tables.stream().map(this::count).toList();
        var payments = jdbc.queryForList("SELECT total,payment_status FROM orders ORDER BY id");
        var inventory = jdbc.queryForList("SELECT stock FROM products ORDER BY id");
        pdf(id, false); pdf(id, true);
        assertThat(tables.stream().map(this::count).toList()).isEqualTo(counts);
        assertThat(jdbc.queryForList("SELECT total,payment_status FROM orders ORDER BY id")).isEqualTo(payments);
        assertThat(jdbc.queryForList("SELECT stock FROM products ORDER BY id")).isEqualTo(inventory);
        assertThat(refunds.requestCount()).isZero();
    }

    @Test void getAndHeadRejectForeignMissingUnauthenticatedAndNonBuyerRequests() throws Exception {
        long id = checkoutOrder(buyer, product, 1);
        var other = sessionWithRole("foreign-receipt@example.test", "COMPRADOR");
        var seller = sessionWithRole("receipt-seller@example.test", "VENDEDOR");
        for (boolean download : List.of(false, true)) {
            for (var method : List.of(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.HEAD)) {
                String path = "/api/orders/" + id + "/receipt";
                perform(other, request(method, path).param("download", String.valueOf(download))
                        .param("accountId", accountIdOf("receipt@example.test").toString()))
                        .andExpect(status().isNotFound());
                perform(buyer, request(method, "/api/orders/" + Long.MAX_VALUE + "/receipt")).andExpect(status().isNotFound());
                mvc.perform(request(method, path)).andExpect(status().isUnauthorized());
                perform(seller, request(method, path)).andExpect(status().isForbidden());
            }
        }
    }
}
