package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RF-111 y RF-112: consulta de pedidos de la tienda y detalle con datos históricos de entrega. */
class SellerOrdersQueryTests extends AbstractIntegrationTest {

    private Session seller;
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        seller = sessionWithRole("seller@example.com", "VENDEDOR");
        seedStore(2, "Otra tienda");
        product = seedProduct(1, "Lámpara", 7, "100.00");
    }

    private ResultActions api(String url) throws Exception {
        return performAsSeller(seller, 1, get(url));
    }

    private void setCreatedAt(long orderId, String timestamp) {
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?", timestamp, orderId);
    }

    // ---------- RF-111 ----------

    @Test
    void rf111_listShowsOnlyTheStoresOrdersInPreparableStatesNewestFirst() throws Exception {
        long older = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        long newer = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        seedOrder(1, "READY_FOR_DISPATCH", product, 1, "100.00");
        seedOrder(1, "CANCELLED", product, 1, "100.00");
        seedOrder(2, "CONFIRMED", seedProduct(2, "Ajena", 3, "5.00"), 1, "5.00");
        setCreatedAt(older, "2026-09-01 10:00:00");
        setCreatedAt(newer, "2026-09-02 10:00:00");

        api("/api/seller/orders").andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].id", contains((int) newer, (int) older)))
                .andExpect(jsonPath("$.content[0].status").value("IN_PREPARATION"))
                .andExpect(jsonPath("$.content[0].paymentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.content[0].itemCount").value(1))
                .andExpect(jsonPath("$.content[0].shippingMethod").value("STANDARD"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void rf111_filtersByStatusesDateRangeAndOrderId() throws Exception {
        long confirmed = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        long ready = seedOrder(1, "READY_FOR_DISPATCH", product, 1, "100.00");
        long cancelled = seedOrder(1, "CANCELLED", product, 1, "100.00");
        setCreatedAt(confirmed, "2026-08-30 23:59:59");
        setCreatedAt(ready, "2026-09-10 08:00:00");
        setCreatedAt(cancelled, "2026-09-10 20:00:00");

        api("/api/seller/orders?status=READY_FOR_DISPATCH&status=CANCELLED").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder((int) ready, (int) cancelled)));
        // Rango de fechas inclusivo en ambos extremos.
        api("/api/seller/orders?status=CONFIRMED&status=READY_FOR_DISPATCH&from=2026-08-30&to=2026-09-10")
                .andExpect(jsonPath("$.content", hasSize(2)));
        api("/api/seller/orders?status=CONFIRMED&status=READY_FOR_DISPATCH&from=2026-08-31&to=2026-09-10")
                .andExpect(jsonPath("$.content[*].id", contains((int) ready)));
        api("/api/seller/orders?status=CONFIRMED&status=READY_FOR_DISPATCH&to=2026-08-30")
                .andExpect(jsonPath("$.content[*].id", contains((int) confirmed)));
        api("/api/seller/orders?orderId=" + cancelled + "&status=CANCELLED")
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].id").value(cancelled));
        api("/api/seller/orders?orderId=" + cancelled).andExpect(jsonPath("$.content", empty())); // estado por defecto
    }

    @Test
    void rf111_paginatesWithAMaximumPageSizeOfOneHundred() throws Exception {
        for (int i = 0; i < 5; i++) {
            seedOrder(1, "CONFIRMED", product, 1, "100.00");
        }

        api("/api/seller/orders?size=2&page=0").andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3));
        api("/api/seller/orders?size=2&page=2").andExpect(jsonPath("$.content", hasSize(1)));
        api("/api/seller/orders?size=100").andExpect(status().isOk());
        api("/api/seller/orders?size=101").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
        api("/api/seller/orders?size=0").andExpect(status().isBadRequest());
        api("/api/seller/orders?page=-1").andExpect(status().isBadRequest());
    }

    @Test
    void rf111_invalidFiltersAreRejectedWith400() throws Exception {
        api("/api/seller/orders?status=INVENTADO").andExpect(status().isBadRequest());
        api("/api/seller/orders?from=ayer").andExpect(status().isBadRequest());
        api("/api/seller/orders?orderId=abc").andExpect(status().isBadRequest());
        api("/api/seller/orders?from=2026-09-10&to=2026-09-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    // ---------- RF-112 ----------

    @Test
    void rf112_detailShowsItemsPaymentInventoryDeliverySnapshotAndHistory() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 2, "100.00");

        api("/api/seller/orders/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order)).andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.total").value(200.0)).andExpect(jsonPath("$.shippingMethod").value("STANDARD"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productId").value(product))
                .andExpect(jsonPath("$.items[0].name").value("Lámpara"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(100.0))
                .andExpect(jsonPath("$.items[0].subtotal").value(200.0))
                .andExpect(jsonPath("$.items[0].currentStock").value(7))
                .andExpect(jsonPath("$.items[0].inventoryConsistent").value(true))
                .andExpect(jsonPath("$.delivery.recipientName").value("Ana Comprador"))
                .andExpect(jsonPath("$.delivery.street").value("Calle 1 # 2-3"))
                .andExpect(jsonPath("$.delivery.city").value("Bogotá"))
                .andExpect(jsonPath("$.delivery.department").value("Cundinamarca"))
                .andExpect(jsonPath("$.delivery.postalCode").value("110111"))
                .andExpect(jsonPath("$.delivery.phone").value("+57 300 123-4567"))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].toStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.history[0].fromStatus").value(nullValue()))
                .andExpect(jsonPath("$.shipment").value(nullValue()))
                .andExpect(jsonPath("$.openIssues", empty()));
    }

    @Test
    void rf112_deliveryDataDoesNotChangeWhenTheBuyersAddressChangesLater() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        jdbc.update("UPDATE addresses SET street = 'Nueva calle 99', city = 'Medellín', phone = '000'");

        api("/api/seller/orders/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.street").value("Calle 1 # 2-3"))
                .andExpect(jsonPath("$.delivery.city").value("Bogotá"))
                .andExpect(jsonPath("$.delivery.phone").value("+57 300 123-4567"));
    }

    @Test
    void rf112_detailFlagsProductsWhoseInventoryIsInconsistent() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        jdbc.update("DELETE FROM products WHERE id = ?", product);

        api("/api/seller/orders/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].currentStock").value(nullValue()))
                .andExpect(jsonPath("$.items[0].inventoryConsistent").value(false));
    }

    @Test
    void rf112_detailIncludesTheShipmentReferenceAndOpenIssuesWhenTheyExist() throws Exception {
        long order = seedOrder(1, "READY_FOR_DISPATCH", product, 1, "100.00");
        jdbc.update("""
                INSERT INTO shipments(order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at)
                VALUES (?, 'SHP-9', 'TRK-9', ?, 'CREATED', NOW(6))""", order, "order-" + order);
        jdbc.update("""
                INSERT INTO order_issues(order_id, type, description, status, reported_by_type, created_at)
                VALUES (?, 'DAMAGED_PRODUCT', 'Caja golpeada', 'OPEN', 'SELLER', NOW(6)),
                       (?, 'OTHER', 'Ya resuelta', 'RESOLVED', 'SELLER', NOW(6))""", order, order);

        api("/api/seller/orders/" + order).andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.shipmentId").value("SHP-9"))
                .andExpect(jsonPath("$.shipment.trackingCode").value("TRK-9"))
                .andExpect(jsonPath("$.shipment.status").value("CREATED"))
                .andExpect(jsonPath("$.openIssues", hasSize(1)))
                .andExpect(jsonPath("$.openIssues[0].type").value("DAMAGED_PRODUCT"));
    }

    // ---------- RNF-003 ----------

    @Test
    void rnf003_anotherStoreCannotSeeOrListTheOrder() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");

        performAsSeller(seller, 2, get("/api/seller/orders/" + order)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        performAsSeller(seller, 2, get("/api/seller/orders")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
        api("/api/seller/orders/999999").andExpect(status().isNotFound());
    }

    @Test
    void rnf003_identityIsRequiredAndOnlyTheSellerRoleIsAccepted() throws Exception {
        seedOrder(1, "CONFIRMED", product, 1, "100.00");

        mvc.perform(get("/api/seller/orders")).andExpect(status().isUnauthorized()); // sin sesión
        perform(seller, get("/api/seller/orders")).andExpect(status().isUnauthorized()) // sesión sin tienda
                .andExpect(jsonPath("$.code").value("STORE_IDENTITY_MISSING"));
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        performAsSeller(buyer, 1, get("/api/seller/orders")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_ROLE_REQUIRED"));
    }

    @Test
    void rnf003_theStoreIsNeverTakenFromTheQueryString() throws Exception {
        seedOrder(1, "CONFIRMED", product, 1, "100.00");

        performAsSeller(seller, 2, get("/api/seller/orders?storeId=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
    }
}
