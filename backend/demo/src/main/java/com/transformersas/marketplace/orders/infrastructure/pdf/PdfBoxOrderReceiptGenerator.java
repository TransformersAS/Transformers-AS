package com.transformersas.marketplace.orders.infrastructure.pdf;

import com.transformersas.marketplace.orders.domain.model.*;
import com.transformersas.marketplace.orders.domain.port.OrderReceiptPdfGenerator;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Solo dibuja texto con PDFBox: no interpreta HTML, enlaces, JavaScript ni instrucciones aportadas por usuarios. */
@Component
public class PdfBoxOrderReceiptGenerator implements OrderReceiptPdfGenerator {
    private static final String UNKNOWN = "Información histórica no disponible";
    private static final String DISCLAIMER = "Este comprobante no constituye una factura fiscal";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String FONT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    @Override
    public byte[] generate(Order order, ZonedDateTime generatedAt) {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
             var input = PdfBoxOrderReceiptGenerator.class.getResourceAsStream(FONT)) {
            if (input == null) throw new IOException("Fuente PDF no disponible");
            var font = PDType0Font.load(document, input);
            document.getDocumentInformation().setTitle("Comprobante de compra - pedido " + order.id());
            try (var page = new Pages(document, font)) {
                page.text("Pedido #" + order.id());
                page.text("Fecha de creación del pedido: " + (order.createdAt() == null ? UNKNOWN : DATE.format(order.createdAt())));
                page.text("Generado al consultar: " + DATE.format(generatedAt) + " (" + generatedAt.getZone() + ")");
                page.text("Documento generado con los datos registrados y estados actuales; no es un original inmutable.");
                page.text("Vendedor: " + text(order.sellerNameSnapshot()));
                provenance(page, "Asociación del vendedor", order.provenance().sellerSource());
                page.text("Estado actual del pedido: " + status(order.status()));
                page.text("Estado financiero actual: " + payment(order.paymentStatus()));
                if (order.provenance().financialUpdateVerified()) page.text("Actualización financiera respaldada por una confirmación externa registrada.");
                else provenance(page, "Estado financiero", order.provenance().currentPaymentSource(order.paymentStatus()));
                if (order.transactionId() != null && !order.transactionId().isBlank()) page.text("Transacción: " + order.transactionId());
                page.text(" ");
                page.text("Productos comprados");
                int number = 1;
                for (var item : order.items()) {
                    page.text(number++ + ". " + text(item.productName()));
                    page.text("Cantidad: " + item.quantity() + " | Precio unitario histórico: " + money(item.unitPrice()));
                    page.text("Subtotal del producto: " + money(item.subtotal()));
                    page.text(" ");
                }
                // Suma de subtotales históricos existentes; no reconstruye descuentos, envío ni total pagado.
                BigDecimal subtotal = order.items().isEmpty() || order.items().stream().anyMatch(i -> i.subtotal() == null)
                        ? null : order.items().stream().map(OrderItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
                page.text("Subtotal de productos: " + money(subtotal));
                page.text("Descuento aplicado: " + money(order.discountAmount()));
                page.text("Costo del envío: " + money(order.shippingCost()));
                page.text("Total original de la compra: " + money(order.total()));
                page.text(" ");
                page.text(order.provenance().deliverySource() == OrderProvenance.Source.CAPTURED_AT_PURCHASE
                        ? "Dirección de entrega utilizada" : "Dirección de entrega registrada");
                provenance(page, "Dirección de entrega", order.provenance().deliverySource());
                DeliverySnapshot d = order.delivery();
                if (d == null) page.text(UNKNOWN);
                else {
                    page.text("Destinatario: " + text(d.recipientName()));
                    page.text("Dirección: " + text(d.street()));
                    page.text("Ciudad: " + text(d.city()) + " | Departamento: " + text(d.department()));
                    page.text("Código postal: " + text(d.postalCode()));
                    page.text("Teléfono: " + text(d.phone()));
                }
                page.text("Método de envío: " + text(order.shippingMethod()));
                if (page.escapedGlyph) page.text("Los caracteres sin glifo se representan mediante su código Unicode [U+...].");
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar el comprobante", exception);
        }
    }

    private static void provenance(Pages page, String field, OrderProvenance.Source source) throws IOException {
        if (source == OrderProvenance.Source.UNKNOWN) page.text(field + ": procedencia histórica no verificada; el valor registrado no demuestra el dato original de la compra.");
        if (source == OrderProvenance.Source.MIGRATED) page.text(field + ": dato incorporado por migración; no fue capturado originalmente al comprar.");
    }

    private static String text(String value) { return value == null || value.isBlank() ? UNKNOWN : value; }
    private static String money(BigDecimal value) {
        return value == null ? UNKNOWN : "$ " + value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
    private static String status(OrderStatus status) {
        if (status == null) return UNKNOWN;
        return switch (status) {
            case CONFIRMED -> "Confirmado";
            case IN_PREPARATION -> "En preparación";
            case READY_FOR_DISPATCH -> "Listo para despacho";
            case PICKED_UP -> "Recogido";
            case IN_TRANSIT -> "En camino";
            case DELIVERED -> "Entregado";
            case DELIVERY_EXCEPTION -> "Novedad en la entrega";
            case DELIVERY_ATTEMPT_FAILED -> "Intento de entrega fallido";
            case RETURNED_TO_SELLER -> "Retornado al vendedor";
            case CANCELLED -> "Cancelado";
            case CANCELLATION_REQUESTED -> "Cancelación solicitada";
        };
    }
    private static String payment(OrderPaymentStatus status) {
        if (status == null) return UNKNOWN;
        return switch (status) {
            case APPROVED -> "Aprobado";
            case REFUND_PENDING -> "Reembolso pendiente";
            case REFUNDED -> "Reembolsado";
        };
    }

    private static final class Pages implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private PDPageContentStream stream;
        private float y;
        private boolean escapedGlyph;
        private static final float MARGIN = 48;
        private static final float WIDTH = PDRectangle.A4.getWidth() - MARGIN * 2;

        Pages(PDDocument document, PDType0Font font) throws IOException {
            this.document = document; this.font = font; newPage();
        }
        private void newPage() throws IOException {
            if (stream != null) stream.close();
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            draw("Comprobante de compra", 17, 797);
            draw(DISCLAIMER, 10, 776);
            draw("Página " + document.getNumberOfPages(), 9, 30);
            y = 747;
        }
        private void draw(String value, float size, float at) throws IOException {
            stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(MARGIN, at);
            stream.showText(value); stream.endText();
        }
        private String safe(String value) throws IOException {
            var result = new StringBuilder();
            for (int code : Normalizer.normalize(value, Normalizer.Form.NFC).codePoints().toArray()) {
                if (Character.isISOControl(code) || Character.isWhitespace(code)) result.append(' ');
                else if (font.hasGlyph(code)) result.appendCodePoint(code);
                else { result.append("[U+%04X]".formatted(code)); escapedGlyph = true; }
            }
            return result.toString();
        }
        void text(String value) throws IOException {
            String remaining = safe(value);
            while (!remaining.isEmpty()) {
                int end = 0;
                float width = 0;
                while (end < remaining.length()) {
                    int next = remaining.offsetByCodePoints(end, 1);
                    float glyphWidth = font.getStringWidth(remaining.substring(end, next)) / 1000 * 10;
                    if (width + glyphWidth > WIDTH) break;
                    width += glyphWidth;
                    end = next;
                }
                if (end < remaining.length()) {
                    int space = remaining.lastIndexOf(' ', end);
                    if (space > 0) end = space;
                }
                if (y < 62) newPage();
                draw(remaining.substring(0, end), 10, y);
                y -= 15;
                remaining = remaining.substring(end).stripLeading();
            }
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
