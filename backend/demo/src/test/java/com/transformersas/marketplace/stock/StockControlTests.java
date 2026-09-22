package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-15: el vendedor controla el inventario de su tienda (existencias, entradas, ajustes, mínimos y alertas). */
class StockControlTests extends AbstractIntegrationTest {

    private static final String API = "/api/seller/inventory";

    @Autowired private SellerInventoryService inventory;

    private Session seller;

    @BeforeEach
    void setUp() throws Exception {
        // Desde CU-18 el vendedor debe ser el dueño de la tienda cuyo X-Store-Id envía.
        seller = sellerOfStore("seller@example.com", 1);
        seedStore(2, "Otra tienda");
    }

    // ---------- Ayudas ----------

    private ResultActions api(MockHttpServletRequestBuilder request) throws Exception {
        return performAsSeller(seller, 1, request);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return api(request.contentType("application/json").content(body));
    }

    private ResultActions entry(long productId, String body) throws Exception {
        return send(post(API + "/" + productId + "/entries"), body);
    }

    private ResultActions adjustment(long productId, String body) throws Exception {
        return send(post(API + "/" + productId + "/adjustments"), body);
    }

    private ResultActions minimum(long productId, int minStock) throws Exception {
        return send(put(API + "/" + productId + "/minimum"), "{\"minStock\":" + minStock + "}");
    }

    private void reserve(long productId, int quantity, String status, LocalDateTime expiresAt) {
        jdbc.update("INSERT INTO inventory_reservations(product_id, quantity, status, created_at, expires_at) "
                        + "VALUES (?,?,?,?,?)", productId, quantity, status, Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(expiresAt));
    }

    private void setStatus(long productId, String status) {
        jdbc.update("UPDATE products SET status = ?, active = ? WHERE id = ?", status, "ACTIVE".equals(status),
                productId);
    }

    private int stockOf(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private int lowStockNotifications() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'LOW_STOCK'", Integer.class);
    }

    // ---------- Consultar existencias ----------

    @Test
    void listaExistenciasConReservadoYDisponible() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");
        reserve(id, 3, "ACTIVE", LocalDateTime.now().plusMinutes(10));
        // No cuentan: una reserva vencida, una ya confirmada y otra liberada.
        reserve(id, 4, "ACTIVE", LocalDateTime.now().minusMinutes(10));
        reserve(id, 2, "CONFIRMED", LocalDateTime.now().plusMinutes(10));
        reserve(id, 1, "RELEASED", LocalDateTime.now().plusMinutes(10));

        api(get(API)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].productId").value(id))
                .andExpect(jsonPath("$[0].name").value("Lámpara"))
                .andExpect(jsonPath("$[0].stock").value(10))
                .andExpect(jsonPath("$[0].reserved").value(3))
                .andExpect(jsonPath("$[0].available").value(7))
                .andExpect(jsonPath("$[0].minStock").value(0))
                .andExpect(jsonPath("$[0].lowStock").value(false));
    }

    @Test
    void laListaSoloMuestraLosProductosDeMiTiendaYNoLosRetirados() throws Exception {
        seedProduct(1, "Mío", 5, "1000");
        seedProduct(2, "De otra tienda", 5, "1000");
        long retired = seedProduct(1, "Retirado", 5, "1000");
        setStatus(retired, "RETIRED");

        api(get(API)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Mío"));
    }

    @Test
    void filtraPorTextoYPorStockBajo() throws Exception {
        long lamp = seedProduct(1, "Lámpara", 2, "1000");
        seedProduct(1, "Mesa", 50, "1000");
        minimum(lamp, 5).andExpect(status().isOk());

        api(get(API).param("q", "mes")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Mesa"));
        api(get(API).param("onlyLow", "true")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Lámpara"))
                .andExpect(jsonPath("$[0].lowStock").value(true));
        // Ordenado por nombre.
        api(get(API)).andExpect(jsonPath("$[*].name", contains("Lámpara", "Mesa")));
    }

    // ---------- Entradas ----------

    @Test
    void laEntradaSumaStockYQuedaEnElHistorial() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");

        entry(id, "{\"quantity\":15,\"reason\":\"  Pedido al proveedor  \"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.stock").value(25))
                .andExpect(jsonPath("$.available").value(25));

        assertThat(stockOf(id)).isEqualTo(25);
        api(get(API + "/" + id + "/movements")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("ENTRY"))
                .andExpect(jsonPath("$[0].quantity").value(15))
                .andExpect(jsonPath("$[0].stockAfter").value(25))
                .andExpect(jsonPath("$[0].reason").value("Pedido al proveedor"));
        assertThat(jdbc.queryForObject("SELECT actor_account_id FROM stock_movements", Long.class))
                .isEqualTo(accountIdOf("seller@example.com"));
    }

    @Test
    void elMotivoDeLaEntradaEsOpcional() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");

        entry(id, "{\"quantity\":1,\"reason\":\"   \"}").andExpect(status().isCreated());

        api(get(API + "/" + id + "/movements")).andExpect(jsonPath("$[0].reason").value(nullValue()));
    }

    @Test
    void laEntradaExigeUnaCantidadPositiva() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");

        entry(id, "{\"quantity\":0}").andExpect(status().isBadRequest());
        entry(id, "{\"quantity\":-3}").andExpect(status().isBadRequest());
        entry(id, "{}").andExpect(status().isBadRequest());

        assertThat(stockOf(id)).isEqualTo(10);
        assertThat(count("stock_movements")).isZero();
    }

    // ---------- Ajustes ----------

    @Test
    void elAjusteFijaElConteoRealYGuardaLaDiferencia() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");

        adjustment(id, "{\"newStock\":7,\"reason\":\"Conteo físico\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.stock").value(7));

        api(get(API + "/" + id + "/movements")).andExpect(jsonPath("$[0].type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$[0].quantity").value(-3))
                .andExpect(jsonPath("$[0].stockAfter").value(7))
                .andExpect(jsonPath("$[0].reason").value("Conteo físico"));
    }

    @Test
    void elAjustePuedeSubirElStock() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");

        adjustment(id, "{\"newStock\":12,\"reason\":\"Aparecieron unidades\"}").andExpect(status().isCreated());

        api(get(API + "/" + id + "/movements")).andExpect(jsonPath("$[0].quantity").value(2));
    }

    @Test
    void elAjusteExigeMotivoYUnConteoValido() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");

        adjustment(id, "{\"newStock\":5}").andExpect(status().isBadRequest());
        adjustment(id, "{\"newStock\":5,\"reason\":\"   \"}").andExpect(status().isBadRequest());
        adjustment(id, "{\"newStock\":-1,\"reason\":\"x\"}").andExpect(status().isBadRequest());
        adjustment(id, "{\"reason\":\"x\"}").andExpect(status().isBadRequest());

        assertThat(stockOf(id)).isEqualTo(10);
    }

    @Test
    void elAjusteNoPuedeDejarElStockPorDebajoDeLoReservado() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");
        reserve(id, 6, "ACTIVE", LocalDateTime.now().plusMinutes(10));

        adjustment(id, "{\"newStock\":5,\"reason\":\"Conteo\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("6 unidades reservadas")));
        adjustment(id, "{\"newStock\":6,\"reason\":\"Conteo\"}").andExpect(status().isCreated());

        assertThat(stockOf(id)).isEqualTo(6);
    }

    @Test
    void unAjusteIgualAlStockActualSeRechaza() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");

        adjustment(id, "{\"newStock\":10,\"reason\":\"Conteo\"}").andExpect(status().isConflict());

        assertThat(count("stock_movements")).isZero();
    }

    // ---------- Aislamiento entre tiendas y estados ----------

    @Test
    void noSePuedeTocarElInventarioDeOtraTienda() throws Exception {
        long foreign = seedProduct(2, "Ajeno", 10, "1000");

        entry(foreign, "{\"quantity\":5}").andExpect(status().isNotFound());
        adjustment(foreign, "{\"newStock\":1,\"reason\":\"x\"}").andExpect(status().isNotFound());
        minimum(foreign, 3).andExpect(status().isNotFound());
        api(get(API + "/" + foreign + "/movements")).andExpect(status().isNotFound());
        entry(999999, "{\"quantity\":5}").andExpect(status().isNotFound());

        assertThat(stockOf(foreign)).isEqualTo(10);
    }

    @Test
    void unProductoRetiradoNoSeMueve() throws Exception {
        long id = seedProduct(1, "Retirado", 10, "1000");
        setStatus(id, "RETIRED");

        entry(id, "{\"quantity\":5}").andExpect(status().isConflict());
        adjustment(id, "{\"newStock\":1,\"reason\":\"x\"}").andExpect(status().isConflict());
        minimum(id, 3).andExpect(status().isConflict());
    }

    @Test
    void soloUnVendedorPuedeUsarElInventario() throws Exception {
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");

        performAsSeller(buyer, 1, get(API)).andExpect(status().isForbidden());
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
    }

    // ---------- Mínimos y alertas ----------

    @Test
    void elMinimoSeConfiguraYSeValida() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");

        minimum(id, 4).andExpect(status().isOk())
                .andExpect(jsonPath("$.minStock").value(4))
                .andExpect(jsonPath("$.lowStock").value(false));
        minimum(id, -1).andExpect(status().isBadRequest());
        send(put(API + "/" + id + "/minimum"), "{}").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT min_stock FROM products WHERE id = ?", Integer.class, id)).isEqualTo(4);
        assertThat(lowStockNotifications()).isZero();
    }

    @Test
    void alQuedarBajoElMinimoLaTiendaRecibeUnaSolaAlerta() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");
        minimum(id, 5).andExpect(status().isOk());

        adjustment(id, "{\"newStock\":4,\"reason\":\"Conteo\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lowStock").value(true));
        assertThat(lowStockNotifications()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT recipient_type, recipient_id, reference_type, reference_id, message "
                + "FROM notifications WHERE type = 'LOW_STOCK'"))
                .containsEntry("recipient_type", "STORE")
                .containsEntry("recipient_id", 1L)
                .containsEntry("reference_type", "PRODUCT")
                .containsEntry("reference_id", String.valueOf(id));

        // Sigue bajo: no se repite el aviso.
        adjustment(id, "{\"newStock\":3,\"reason\":\"Conteo\"}").andExpect(status().isCreated());
        assertThat(lowStockNotifications()).isEqualTo(1);
    }

    @Test
    void alReponerseElAvisoSeRearmaYVuelveASalirSiBajaOtraVez() throws Exception {
        long id = seedProduct(1, "Lámpara", 3, "50000");
        minimum(id, 5).andExpect(status().isOk());
        assertThat(lowStockNotifications()).isEqualTo(1);

        entry(id, "{\"quantity\":20,\"reason\":\"Reposición\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lowStock").value(false));
        assertThat(lowStockNotifications()).isEqualTo(1);

        adjustment(id, "{\"newStock\":2,\"reason\":\"Conteo\"}").andExpect(status().isCreated());
        assertThat(lowStockNotifications()).isEqualTo(2);
    }

    @Test
    void ponerElMinimoEnCeroApagaLaAlerta() throws Exception {
        long id = seedProduct(1, "Lámpara", 2, "50000");
        minimum(id, 5).andExpect(jsonPath("$.lowStock").value(true));

        minimum(id, 0).andExpect(jsonPath("$.lowStock").value(false));
        assertThat(jdbc.queryForObject("SELECT low_stock_alerted FROM products WHERE id = ?", Boolean.class, id))
                .isFalse();
    }

    @Test
    void unBorradorNoGeneraAlertas() throws Exception {
        long id = seedProduct(1, "Borrador", 0, "1000");
        setStatus(id, "DRAFT");

        minimum(id, 5).andExpect(status().isOk()).andExpect(jsonPath("$.lowStock").value(false));

        assertThat(lowStockNotifications()).isZero();
    }

    @Test
    void laRevisionPeriodicaDetectaLasBajasPorVentas() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");
        minimum(id, 5).andExpect(status().isOk());
        assertThat(inventory.checkAlerts()).isZero();

        // Una venta descuenta el stock sin pasar por el servicio de inventario.
        jdbc.update("UPDATE products SET stock = 2 WHERE id = ?", id);
        assertThat(inventory.checkAlerts()).isEqualTo(1);
        assertThat(lowStockNotifications()).isEqualTo(1);
        assertThat(inventory.checkAlerts()).isZero();

        // Al reponerse por otra vía, la revisión rearma el aviso sin crear otra notificación.
        jdbc.update("UPDATE products SET stock = 30 WHERE id = ?", id);
        assertThat(inventory.checkAlerts()).isEqualTo(1);
        assertThat(lowStockNotifications()).isEqualTo(1);
        assertThat(inventory.checkAlerts()).isZero();
    }

    @Test
    void elPlanificadorLlamaALaRevisionYSobrevivePorFallos() {
        SellerInventoryService service = mock(SellerInventoryService.class);
        when(service.checkAlerts()).thenReturn(2).thenThrow(new IllegalStateException("base caída"));
        StockAlertScheduler scheduler = new StockAlertScheduler(service);

        scheduler.checkLowStock();
        scheduler.checkLowStock();

        verify(service, org.mockito.Mockito.times(2)).checkAlerts();
    }

    // ---------- Historial ----------

    @Test
    void elHistorialMuestraPrimeroLoMasReciente() throws Exception {
        long id = seedProduct(1, "Lámpara", 10, "50000");

        entry(id, "{\"quantity\":5,\"reason\":\"Primera\"}").andExpect(status().isCreated());
        adjustment(id, "{\"newStock\":8,\"reason\":\"Segunda\"}").andExpect(status().isCreated());

        api(get(API + "/" + id + "/movements")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].reason", contains("Segunda", "Primera")));
    }
}
