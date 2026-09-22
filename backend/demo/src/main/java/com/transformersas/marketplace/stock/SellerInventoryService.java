package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductRepository;
import com.transformersas.marketplace.product.ProductStatus;
import com.transformersas.marketplace.reservation.InventoryReservationRepository;
import com.transformersas.marketplace.reservation.ReservationStatus;
import com.transformersas.marketplace.stock.dto.InventoryItemResponse;
import com.transformersas.marketplace.stock.dto.StockMovementResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Controlar el inventario de una tienda (CU-15): consultar existencias, registrar entradas y ajustes, configurar el
 * nivel mínimo y avisar cuando el stock queda bajo. Como en CU-14, cada método recibe la tienda del vendedor
 * autenticado y solo trabaja con productos de esa tienda. El stock sigue siendo products.stock: el vendedor lo indica
 * al publicar y las compras lo descuentan; aquí se corrige y se repone.
 */
@Service
@Transactional
public class SellerInventoryService {

    private final ProductRepository products;
    private final InventoryReservationRepository reservations;
    private final StockMovementRepository movements;
    private final PublishNotificationUseCase notifications;

    public SellerInventoryService(ProductRepository products, InventoryReservationRepository reservations,
                                  StockMovementRepository movements, PublishNotificationUseCase notifications) {
        this.products = products;
        this.reservations = reservations;
        this.movements = movements;
        this.notifications = notifications;
    }

    /** Existencias de la tienda (sin los productos retirados), filtradas por texto y/o solo las que están bajas. */
    @Transactional(readOnly = true)
    public List<InventoryItemResponse> list(Long storeId, String text, boolean onlyLow) {
        String wanted = text == null ? "" : text.strip().toLowerCase();
        return products.findByStoreId(storeId).stream()
                .filter(product -> product.getStatus() != ProductStatus.RETIRED)
                .filter(product -> product.getName().toLowerCase().contains(wanted)
                        || product.getCategory().toLowerCase().contains(wanted))
                .filter(product -> !onlyLow || isLow(product))
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse)
                .toList();
    }

    /** Entrada: llegó mercancía, se suma al stock. */
    public InventoryItemResponse registerEntry(Long storeId, Long actorId, Long productId, int quantity, String reason) {
        Product product = findEditable(storeId, productId);
        product.setStock(product.getStock() + quantity);
        return save(product, new StockMovement(productId, StockMovementType.ENTRY, quantity, product.getStock(),
                clean(reason), actorId));
    }

    /**
     * Ajuste: el vendedor fija el conteo real. No puede quedar por debajo de lo que ya tienen apartado las compras
     * en curso, porque esas unidades ya se prometieron a un comprador.
     */
    public InventoryItemResponse registerAdjustment(Long storeId, Long actorId, Long productId, int newStock,
                                                    String reason) {
        Product product = findEditable(storeId, productId);
        int reserved = reservedUnits(product);
        if (newStock < reserved) {
            throw conflict("El stock no puede ser menor que las " + reserved
                    + " unidades reservadas por compras en curso");
        }
        int difference = newStock - product.getStock();
        if (difference == 0) {
            throw conflict("El conteo es igual al stock actual: no hay nada que ajustar");
        }
        product.setStock(newStock);
        return save(product, new StockMovement(productId, StockMovementType.ADJUSTMENT, difference, newStock,
                reason.strip(), actorId));
    }

    /** Configura el nivel mínimo (0 = sin aviso) y vuelve a evaluar la alerta con el nuevo valor. */
    public InventoryItemResponse setMinimum(Long storeId, Long productId, int minStock) {
        Product product = findEditable(storeId, productId);
        product.setMinStock(minStock);
        updateAlert(product);
        return toResponse(products.save(product));
    }

    @Transactional(readOnly = true)
    public List<StockMovementResponse> movements(Long storeId, Long productId) {
        products.findByIdAndStoreId(productId, storeId).orElseThrow(this::notFound);
        return movements.findTop50ByProductIdOrderByIdDesc(productId).stream().map(StockMovementResponse::from).toList();
    }

    /**
     * Revisa todos los productos con mínimo configurado. Lo llama el planificador cada pocos minutos para detectar
     * los descuentos por ventas, que no pasan por este servicio. Devuelve cuántos productos cambiaron de estado.
     */
    public int checkAlerts() {
        int changed = 0;
        for (Product product : products.findByMinStockGreaterThanOrLowStockAlertedTrue(0)) {
            if (updateAlert(product)) {
                products.save(product);
                changed++;
            }
        }
        return changed;
    }

    // ---------- Métodos auxiliares ----------

    /** Bloquea la fila del producto de esta tienda; un producto retirado ya no se mueve (igual que en CU-14). */
    private Product findEditable(Long storeId, Long productId) {
        Product product = products.findForUpdate(productId, storeId).orElseThrow(this::notFound);
        if (product.getStatus() == ProductStatus.RETIRED) {
            throw conflict("Un producto retirado no se puede modificar");
        }
        return product;
    }

    private InventoryItemResponse save(Product product, StockMovement movement) {
        movements.save(movement);
        updateAlert(product);
        return toResponse(products.save(product));
    }

    private InventoryItemResponse toResponse(Product product) {
        return InventoryItemResponse.from(product, reservedUnits(product), isLow(product));
    }

    private int reservedUnits(Product product) {
        return reservations.sumReservedQuantity(product.getId(), ReservationStatus.ACTIVE, LocalDateTime.now())
                .intValue();
    }

    /** Stock bajo: hay un mínimo configurado, el producto está a la venta (o pausado) y el stock lo alcanzó. */
    private boolean isLow(Product product) {
        boolean inUse = product.getStatus() == ProductStatus.ACTIVE || product.getStatus() == ProductStatus.PAUSED;
        return inUse && product.getMinStock() > 0 && product.getStock() <= product.getMinStock();
    }

    /**
     * Avisa una sola vez al quedar bajo el mínimo y "rearma" el aviso cuando el stock se repone. Devuelve true si
     * el producto cambió (hay que guardarlo).
     */
    private boolean updateAlert(Product product) {
        boolean low = isLow(product);
        if (low && !product.getLowStockAlerted()) {
            notifications.execute(new PublishNotificationCommand(RecipientType.STORE, product.getStoreId(),
                    "LOW_STOCK", "Stock bajo",
                    "El producto \"" + product.getName() + "\" tiene " + product.getStock()
                            + " unidades y su nivel mínimo es " + product.getMinStock()
                            + ". Registra una entrada de mercancía para reponerlo.",
                    "PRODUCT", String.valueOf(product.getId()),
                    "low-stock-" + product.getId() + "-" + System.currentTimeMillis()));
            product.setLowStockAlerted(true);
            return true;
        }
        if (!low && product.getLowStockAlerted()) {
            product.setLowStockAlerted(false);
            return true;
        }
        return false;
    }

    private String clean(String reason) {
        return reason == null || reason.isBlank() ? null : reason.strip();
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado");
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
