package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.returns.domain.model.ReturnLine;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Utilidades de las pruebas de devoluciones (CU-19). Las tablas de devoluciones se limpian antes y después de cada
 * prueba, porque la lista compartida de {@link AbstractIntegrationTest} no las incluye y sus filas referencian pedidos.
 */
abstract class ReturnsTestSupport extends AbstractIntegrationTest {
    static final List<String> RETURN_TABLES = List.of("claim_messages", "claim_evidences", "claims",
            "return_evidence_files", "return_events", "return_information_requests", "return_requests");

    /** Un pedido entregado de una sola línea. */
    record DeliveredOrder(long orderId, long itemId, long productId, long buyerId) {
    }

    @BeforeEach
    @AfterEach
    void cleanReturnTables() {
        RETURN_TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    /** Pedido ENTREGADO de la tienda 1 (2 unidades a 10.00) que consta entregado en {@code deliveredAt}. */
    DeliveredOrder deliveredOrder(long buyerId, LocalDateTime deliveredAt) {
        long product = seedProduct(1, "Lámpara " + System.nanoTime(), 5, "10.00");
        long order = seedOrder(1, "DELIVERED", product, 2, "10.00");
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyerId, order);
        jdbc.update("UPDATE order_status_history SET created_at = ? WHERE order_id = ? AND to_status = 'DELIVERED'",
                deliveredAt, order);
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", Long.class, order);
        return new DeliveredOrder(order, item, product, buyerId);
    }

    static final String RETURNS = "/api/return-requests";
    static final String SELLER_RETURNS = "/api/seller/return-requests";

    static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, format, out);
        return out.toByteArray();
    }

    static MockMultipartFile evidence(String name, byte[] content) {
        return new MockMultipartFile("evidences", name, "image/png", content);
    }

    /** Solicitud JSON de devolución. */
    ResultActions requestReturn(Session session, long orderId, long itemId, String reason, String description)
            throws Exception {
        String body = "{\"orderId\":%d,\"orderItemId\":%d,\"reason\":%s,\"description\":%s}".formatted(orderId, itemId,
                reason == null ? "null" : "\"" + reason + "\"", description == null ? "null" : "\"" + description + "\"");
        return perform(session, post(RETURNS).contentType("application/json").content(body));
    }

    /** Solicitud multipart con imágenes; la petición multipart se firma aquí porque tiene su propio tipo de constructor. */
    ResultActions requestReturnWithImages(Session session, long orderId, long itemId, MockMultipartFile... files)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(RETURNS);
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return mvc.perform(request.param("orderId", String.valueOf(orderId))
                .param("orderItemId", String.valueOf(itemId)).param("reason", "DEFECTIVE")
                .param("description", "No enciende").cookie(session.cookie())
                .header(session.csrfHeader(), session.csrfToken()));
    }

    long idOf(ResultActions result, String field) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get(field).asLong();
    }

    ReturnLine lineOf(DeliveredOrder order) {
        return new ReturnLine(order.itemId(), order.productId(), "Lámpara", 2, new BigDecimal("10.00"));
    }
}
