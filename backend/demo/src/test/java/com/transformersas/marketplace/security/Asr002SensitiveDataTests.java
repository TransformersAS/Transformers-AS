package com.transformersas.marketplace.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Evidencia de ASR-002 (protección de datos sensibles) sobre la aplicación real: seguridad, controladores, MySQL y el
 * adaptador HTTP de Gemini. Solo el proveedor de IA es WireMock. No cambia código de producción: comprueba lo que el
 * sistema ya hace hoy. El cifrado de campos en la base (AES-GCM) no está implementado y por eso no se prueba aquí.
 */
@ExtendWith(OutputCaptureExtension.class)
class Asr002SensitiveDataTests extends AbstractIntegrationTest {

    /** Tarjeta de prueba de Visa: si apareciera guardada en cualquier columna o en un log, la prueba falla. */
    private static final String PAN = "4111111111111111";
    private static final String CVV = "937";
    private static final String TITULAR = "TITULAR PRUEBA ASRDOS";

    static final WireMockServer gemini = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));

    static {
        gemini.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("gemini.base-url", gemini::baseUrl);
        p.add("gemini.api-key", () -> "wiremock-only");
        p.add("gemini.model", () -> "asr-002-model");
    }

    @AfterAll
    static void stop() {
        gemini.stop();
    }

    @BeforeEach
    void reset() {
        gemini.resetAll();
        jdbc.update("DELETE FROM user_interactions");
    }

    // ---------- Limitar exposición: PAN y CVV ----------

    /**
     * Un cliente malicioso o mal hecho envía el número completo de la tarjeta, el CVV y el titular junto al pago. El
     * DTO de pago no tiene esos campos, así que se descartan: no se guardan en ninguna columna de la base, no salen
     * en la respuesta y no quedan en los registros de la aplicación.
     */
    @Test
    void elPagoDescartaPanYCvvYNoLosGuardaNiLosRegistra(CapturedOutput salida) throws Exception {
        long producto = seedProduct(1, "Lámpara ASR", 5, "80000");
        Session comprador = sessionWithRole("tarjeta.asr002@example.test", "COMPRADOR");
        long direccion = seedAddress(comprador);
        perform(comprador, post("/api/cart/items").contentType("application/json")
                .content("{\"productId\":" + producto + ",\"quantity\":1}")).andExpect(status().isCreated());
        String reservas = perform(comprador, post("/api/reservations/cart")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = json.readTree(reservas).valueStream().map(nodo -> nodo.get("id").asLong()).toList();

        String respuesta = perform(comprador, post("/api/payments/process").contentType("application/json").content("""
                {"paymentMethod":"CARD","reservationIds":%s,"addressId":%d,"shippingMethod":"STANDARD",
                 "cardNumber":"%s","cvv":"%s","cardHolder":"%s","expiry":"12/30"}"""
                .formatted(ids, direccion, PAN, CVV, TITULAR)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        long pedido = json.readTree(respuesta).get("orderId").asLong();
        assertThat(pedido).isPositive();
        assertThat(respuesta).doesNotContain(PAN).doesNotContain(TITULAR);
        // Controles: la búsqueda sí encuentra lo que debe estar guardado y la salida capturada es la de la aplicación.
        assertThat(columnasQueContienen("Lámpara ASR")).contains("products.name", "order_items.product_name");
        assertThat(salida.getAll()).contains("type=PURCHASED");

        assertThat(columnasQueContienen(PAN)).as("columnas con el PAN").isEmpty();
        assertThat(columnasQueContienen(TITULAR)).as("columnas con el titular").isEmpty();
        assertThat(salida.getAll()).doesNotContain(PAN).doesNotContain(TITULAR);
    }

    // ---------- Autorizar / limitar acceso: datos de otra cuenta ----------

    /**
     * Dos compradores con el mismo rol. El segundo conoce los ids del pedido y la dirección del primero, pero el
     * sistema responde como si no existieran: no los ve, no los modifica y no puede reclamar sobre esa compra.
     */
    @Test
    void unCompradorNoAccedeALosDatosPrivadosDeOtro() throws Exception {
        long producto = seedProduct(1, "Mesa ASR", 5, "120000");
        Session duena = sessionWithRole("duena.asr002@example.test", "COMPRADOR");
        Session intruso = sessionWithRole("intruso.asr002@example.test", "COMPRADOR");
        long pedido = checkoutOrder(duena, producto, 1);
        long direccion = jdbc.queryForObject(
                "SELECT address_id FROM orders WHERE id = ?", Long.class, pedido);

        perform(intruso, get("/api/orders/{id}", pedido)).andExpect(status().isNotFound());
        perform(intruso, get("/api/orders")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        perform(intruso, get("/api/addresses")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        perform(intruso, put("/api/addresses/{id}", direccion).contentType("application/json").content("""
                {"recipientName":"Intruso","street":"Calle falsa 123","city":"Bogotá","department":"Cundinamarca",
                 "postalCode":"110111","phone":"+57 300 000 0000"}""")).andExpect(status().isNotFound());
        perform(intruso, post("/api/claims").contentType("application/json").content("""
                {"orderId":%d,"productId":%d,"description":"No es mío","evidenceUrls":[]}""".formatted(pedido, producto)))
                .andExpect(status().isNotFound());

        // La dueña sí ve su pedido y su dirección siguen intactos.
        perform(duena, get("/api/orders/{id}", pedido)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT recipient_name FROM addresses WHERE id = ?", String.class, direccion))
                .isEqualTo("Ana Comprador");
    }

    // ---------- Minimizar datos a terceros: Gemini ----------

    /**
     * Para recomendar, el sistema le envía a Gemini solo el historial de productos y el catálogo disponible. El
     * correo, el nombre, la dirección y el teléfono del comprador no salen hacia el proveedor externo.
     */
    @Test
    void geminiRecibeSoloProductosYNoLosDatosPersonalesDelComprador() throws Exception {
        long cafe = seedProduct(1, "Café de origen ASR", 10, "25000");
        seedProduct(1, "Molino manual ASR", 10, "60000");
        String correo = "privada.asr002@example.test";
        Session comprador = sessionWithRole(correo, "COMPRADOR");
        seedAddress(comprador);
        perform(comprador, post("/api/interactions").contentType("application/json")
                .content("{\"interactionType\":\"VIEW\",\"productId\":" + cafe + "}")).andExpect(status().isCreated());
        gemini.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching("/v1beta/models/.*:generateContent"))
                .willReturn(okJson(json.writeValueAsString(Map.of("candidates", List.of(Map.of("content",
                        Map.of("parts", List.of(Map.of("text", "{\"productIds\":[" + cafe + "]}"))))))))));

        perform(comprador, get("/api/recommendations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("AI"));

        var peticiones = gemini.findAll(postRequestedFor(urlPathMatching("/v1beta/models/.*:generateContent")));
        assertThat(peticiones).hasSize(1);
        String enviado = peticiones.getFirst().getBodyAsString();
        assertThat(enviado).contains("Café de origen ASR");
        assertThat(enviado)
                .doesNotContain(correo)
                .doesNotContain("Ana Comprador")
                .doesNotContain("Calle 1 # 2-3")
                .doesNotContain("300 123-4567");
    }

    /** Busca el texto en todas las columnas de texto de todas las tablas de la base de pruebas. */
    private List<String> columnasQueContienen(String texto) {
        List<Map<String, Object>> columnas = jdbc.queryForList("""
                SELECT table_name AS t, column_name AS c FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND data_type IN ('char','varchar','tinytext','text','mediumtext','longtext','json')""");
        return columnas.stream()
                // Algunas columnas usan ascii_bin (por ejemplo, hashes): se convierten para comparar cualquier texto.
                .filter(col -> jdbc.queryForObject("SELECT COUNT(*) FROM `" + col.get("t") + "` WHERE CONVERT(`"
                        + col.get("c") + "` USING utf8mb4) LIKE ?", Integer.class, "%" + texto + "%") > 0)
                .map(col -> col.get("t") + "." + col.get("c"))
                .toList();
    }
}
