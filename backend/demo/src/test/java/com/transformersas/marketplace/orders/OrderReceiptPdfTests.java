package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.orders.domain.model.*;
import com.transformersas.marketplace.orders.infrastructure.pdf.PdfBoxOrderReceiptGenerator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class OrderReceiptPdfTests {
    private final PdfBoxOrderReceiptGenerator generator = new PdfBoxOrderReceiptGenerator();
    private final ZonedDateTime generatedAt = ZonedDateTime.of(2026, 10, 31, 14, 0, 0, 0, ZoneId.of("America/Bogota"));
    private Order order(List<OrderItem> items, BigDecimal discount, BigDecimal shipping, String seller) {
        return new Order(123L, 1L, OrderStatus.CANCELLED, OrderPaymentStatus.REFUND_PENDING, new BigDecimal("123.45"),
                1L, 1L, "STANDARD", new DeliverySnapshot("Ana María", "Calle Ñ & <script>", "Bogotá", "Cundinamarca", null, "123"),
                "MP-test", LocalDateTime.of(2026, 1, 1, 12, 0), items, discount, shipping, seller);
    }
    private OrderItem line(String name) { return new OrderItem(1L, name, 2, new BigDecimal("25.00"), new BigDecimal("50.00")); }

    @Test void validPdfContainsHistoricalFinancialDataAndGenerationNotice() throws Exception {
        byte[] pdf = generator.generate(order(List.of(line("Café Ñandú")), BigDecimal.ZERO, BigDecimal.ZERO, "Tienda histórica"), generatedAt);
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (var doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Comprobante de compra", "Este comprobante no constituye una factura fiscal", "Pedido #123",
                    "Fecha de creación del pedido: 01/01/2026 12:00:00", "Generado al consultar: 31/10/2026 14:00:00",
                    "Tienda histórica", "Café Ñandú", "Cantidad: 2", "Precio unitario histórico: $ 25.00",
                    "Subtotal de productos: $ 50.00", "Descuento aplicado: $ 0.00", "Costo del envío: $ 0.00",
                    "Total original de la compra: $ 123.45", "Ana María", "Calle Ñ & <script>", "Bogotá",
                    "Método de envío: STANDARD", "Estado actual del pedido: Cancelado",
                    "Estado financiero actual: Reembolso pendiente", "Transacción: MP-test");
            assertThat(doc.getDocumentCatalog().getOpenAction()).isNull();
            assertThat(doc.getPage(0).getAnnotations()).isEmpty();
        }
    }

    @Test void appliedDiscountAndShippingUseStoredAmountsWithoutChangingTotal() throws Exception {
        try (var doc = Loader.loadPDF(generator.generate(order(List.of(line("Original")),
                new BigDecimal("12.34"), new BigDecimal("3.21"), "Tienda"), generatedAt))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Descuento aplicado: $ 12.34",
                    "Costo del envío: $ 3.21", "Total original de la compra: $ 123.45");
        }
    }

    @Test void unknownValuesAreNotZeroAndTotalIsNeverReconstructed() throws Exception {
        try (var doc = Loader.loadPDF(generator.generate(order(List.of(line("Original")), null, null, null), generatedAt))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Vendedor: Información histórica no disponible",
                    "Descuento aplicado: Información histórica no disponible", "Costo del envío: Información histórica no disponible",
                    "Total original de la compra: $ 123.45").doesNotContain("Descuento aplicado: $ 0.00", "Costo del envío: $ 0.00");
        }
    }

    @Test void longTextsSpecialCharactersAndManyProductsWrapAcrossPagesWithoutActions() throws Exception {
        var lines = IntStream.rangeClosed(1, 90).mapToObj(i -> line("Producto " + i + " " + "Lámpara (Ñ) & <b> \\ ".repeat(20))).toList();
        try (var doc = Loader.loadPDF(generator.generate(order(lines, BigDecimal.ZERO, BigDecimal.TEN, "Histórica"), generatedAt))) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Producto 1 ", "Producto 90 ", "Subtotal de productos: $ 4500.00", "Total original de la compra: $ 123.45");
            var stripper = new PDFTextStripper();
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                assertThat(stripper.getText(doc)).contains("Comprobante de compra", "Este comprobante no constituye una factura fiscal", "Página " + page);
                assertThat(doc.getPage(page - 1).getAnnotations()).isEmpty();
            }
        }
    }

    @Test void unsupportedGlyphsRemainIdentifiableInsteadOfBreakingTheDocument() throws Exception {
        try (var doc = Loader.loadPDF(generator.generate(order(List.of(line("Robot 🚀")), BigDecimal.ZERO, BigDecimal.ZERO, "Tienda"), generatedAt))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Robot [U+1F680]", "código Unicode [U+...]");
        }
    }
}
