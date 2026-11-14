package com.transformersas.marketplace.promotions;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-16: el vendedor diseña los cupones de su tienda (crear, modificar, activar, desactivar, eliminar, conflictos). */
class SellerPromotionTests extends AbstractIntegrationTest {

    private static final String API = "/api/seller/promotions";

    private Session seller;

    @BeforeEach
    void setUp() throws Exception {
        // La limpieza común conserva la tienda 1; sus cupones se borran aquí.
        jdbc.update("DELETE FROM promotions");
        seller = sellerOfStore("vendedor.cu16@example.com", 1);
        seedStore(2, "Otra tienda");
    }

    // ---------- Ayudas ----------

    private ResultActions api(MockHttpServletRequestBuilder request) throws Exception {
        return perform(seller, request);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return api(request.contentType("application/json").content(body));
    }

    private static String coupon(String code, String type, String value, String products) {
        return coupon(code, type, value, products, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
    }

    private static String coupon(String code, String type, String value, String products, LocalDateTime start,
                                 LocalDateTime end) {
        return """
                {"name":"Cupón %s","code":"%s","discountType":"%s","discountValue":%s,"startsAt":"%s",
                 "endsAt":"%s","productIds":%s}""".formatted(code, code, type, value, start, end, products);
    }

    private long create(String body) throws Exception {
        String response = send(post(API), body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    // ---------- Crear ----------

    @Test
    void creaUnCuponInactivoConElCodigoEnMayusculas() throws Exception {
        send(post(API), coupon("verano20", "PERCENT", "20", "[]")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("VERANO20"))
                .andExpect(jsonPath("$.discountType").value("PERCENT"))
                .andExpect(jsonPath("$.discountValue").value(20))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.productIds", empty()));

        api(get(API)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void guardaSoloProductosDeMiTienda() throws Exception {
        long mine = seedProduct(1, "Lámpara", 5, "50000");
        long other = seedProduct(2, "Ajeno", 5, "50000");

        send(post(API), coupon("LAMPARA", "AMOUNT", "5000", "[" + mine + "]")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.productIds", contains((int) mine)));
        send(post(API), coupon("AJENO", "AMOUNT", "5000", "[" + other + "]")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROMOTION_PRODUCT_NOT_FOUND"));
    }

    @Test
    void rechazaDatosInvalidos() throws Exception {
        send(post(API), coupon("MUCHO", "PERCENT", "150", "[]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROMOTION_PERCENT_TOO_HIGH"));
        send(post(API), coupon("CERO", "AMOUNT", "0", "[]")).andExpect(status().isBadRequest());
        send(post(API), coupon("a b", "AMOUNT", "100", "[]")).andExpect(status().isBadRequest());
        LocalDateTime now = LocalDateTime.now();
        send(post(API), coupon("FECHAS", "AMOUNT", "100", "[]", now, now.minusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROMOTION_DATES"));
    }

    @Test
    void noRepiteCodigosNiUsaElReservado() throws Exception {
        create(coupon("UNICO", "AMOUNT", "100", "[]"));
        send(post(API), coupon("unico", "AMOUNT", "200", "[]")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_CODE_TAKEN"));
        send(post(API), coupon("desc10", "PERCENT", "10", "[]")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_CODE_RESERVED"));
    }

    // ---------- Modificar, activar, desactivar, eliminar ----------

    @Test
    void modificaActivaDesactivaYElimina() throws Exception {
        long id = create(coupon("CICLO", "AMOUNT", "1000", "[]"));

        send(put(API + "/" + id), coupon("CICLO", "PERCENT", "15", "[]")).andExpect(status().isOk())
                .andExpect(jsonPath("$.discountType").value("PERCENT"))
                .andExpect(jsonPath("$.discountValue").value(15));
        api(post(API + "/" + id + "/activate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        api(post(API + "/" + id + "/deactivate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        api(delete(API + "/" + id)).andExpect(status().isNoContent());
        api(get(API)).andExpect(jsonPath("$", empty()));
    }

    @Test
    void elEstadoDistingueProgramadoYVencido() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        long future = create(coupon("FUTURO", "AMOUNT", "100", "[]", now.plusDays(2), now.plusDays(5)));
        api(post(API + "/" + future + "/activate")).andExpect(jsonPath("$.status").value("SCHEDULED"));

        long past = create(coupon("PASADO", "AMOUNT", "100", "[]", now.minusDays(5), now.minusDays(2)));
        api(post(API + "/" + past + "/activate")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_EXPIRED"));
        // Si ya estaba activo cuando venció, la lista lo muestra como vencido.
        jdbc.update("UPDATE promotions SET active = TRUE WHERE id = ?", past);
        api(get(API)).andExpect(jsonPath("$[?(@.code == 'PASADO')].status").value("EXPIRED"));
    }

    @Test
    void noVeNiTocaCuponesDeOtraTienda() throws Exception {
        Session other = sellerOfStore("otro.vendedor.cu16@example.com", 2);
        String response = perform(other, post(API).contentType("application/json")
                .content(coupon("AJENO", "AMOUNT", "100", "[]"))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long foreign = json.readTree(response).get("id").asLong();

        api(get(API)).andExpect(jsonPath("$", empty()));
        api(post(API + "/" + foreign + "/activate")).andExpect(status().isNotFound());
        api(delete(API + "/" + foreign)).andExpect(status().isNotFound());
    }

    // ---------- Conflictos ----------

    @Test
    void avisaDeCuponesActivosQueSeCruzan() throws Exception {
        long lamp = seedProduct(1, "Lámpara", 5, "50000");
        long table = seedProduct(1, "Mesa", 5, "90000");
        long all = create(coupon("TODO", "PERCENT", "10", "[]"));
        api(post(API + "/" + all + "/activate")).andExpect(status().isOk());

        // Toda la tienda choca con cualquier otro cupón que se cruce en fechas.
        send(post(API), coupon("LAMPARA", "AMOUNT", "5000", "[" + lamp + "]")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.conflicts", contains("Cupón TODO (TODO)")));

        api(post(API + "/" + all + "/deactivate")).andExpect(status().isOk());
        long lampOnly = create(coupon("SOLOLAMP", "AMOUNT", "1000", "[" + lamp + "]"));
        api(post(API + "/" + lampOnly + "/activate")).andExpect(status().isOk());
        // Productos distintos: no hay conflicto.
        send(post(API), coupon("MESA", "AMOUNT", "1000", "[" + table + "]")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.conflicts", empty()));
    }

    // ---------- Tienda suspendida (CU-27) ----------

    @Test
    void unaTiendaSuspendidaNoCreaNiActivaPeroSiDesactiva() throws Exception {
        long id = create(coupon("ANTES", "AMOUNT", "100", "[]"));
        api(post(API + "/" + id + "/activate")).andExpect(status().isOk());
        jdbc.update("UPDATE stores SET status = 'SUSPENDED', status_reason = 'Prueba' WHERE id = 1");

        send(post(API), coupon("NUEVO", "AMOUNT", "100", "[]")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_SUSPENDED"));
        api(post(API + "/" + id + "/activate")).andExpect(status().isConflict());
        api(post(API + "/" + id + "/deactivate")).andExpect(status().isOk());
    }

    @Test
    void unCompradorNoPuedeGestionarCupones() throws Exception {
        Session buyer = sessionWithRole("comprador.cu16@example.com", "COMPRADOR");
        perform(buyer, get(API)).andExpect(status().isForbidden());
    }
}
