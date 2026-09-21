/** Casos de uso de logística. */
package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.CreateShipmentCommand;
import com.transformersas.marketplace.logistics.application.dto.ShipmentResult;
import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.logistics.domain.model.ShipmentReceipt;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.domain.model.ShipmentStatus;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentRepository;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Crea el envío de un pedido de forma idempotente (RF-114, A5, A6, RNF-043).
 *
 * <p>NO es transaccional a propósito: la llamada al proveedor nunca ocurre dentro de una transacción de BD.
 * Política de resiliencia: sin reintentos automáticos aquí; el reintento es manual y seguro porque (1) el proveedor
 * deduplica por Idempotency-Key "order-{id}" y (2) la fila local tiene UNIQUE por pedido.
 */
@Service
public class CreateShipmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateShipmentUseCase.class);

    private final LogisticsGateway gateway;
    private final ShipmentRepository shipments;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;

    public CreateShipmentUseCase(LogisticsGateway gateway, ShipmentRepository shipments, AuditRecorder audit,
                                 TransactionTemplate transaction) {
        this.gateway = gateway;
        this.shipments = shipments;
        this.audit = audit;
        this.transaction = transaction;
    }

    public ShipmentResult execute(CreateShipmentCommand command) {
        ShipmentRequest request = command.request();
        Long orderId = request.orderId();

        var existing = shipments.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Envío ya existente, se reutiliza la referencia orderId={}", orderId);
            return new ShipmentResult(existing.get(), false);
        }

        String key = request.idempotencyKey();
        ShipmentReceipt receipt;
        try {
            receipt = gateway.createShipment(request, key);
        } catch (LogisticsRejectedException | LogisticsUnavailableException failure) {
            log.warn("Falló la creación del envío orderId={} tipo={}", orderId, failure.getClass().getSimpleName());
            audit.record(command.actorType(), command.actorId(), "SHIPMENT_REQUEST", "ORDER", orderId,
                    AuditOutcome.FAILURE, Map.of("idempotencyKey", key, "failure", failure.getClass().getSimpleName()));
            throw failure;
        }

        // Insert + auditoría en una transacción corta y posterior a la llamada externa.
        ShipmentRepository.Insertion insertion = transaction.execute(status -> {
            var result = shipments.insertIfAbsent(new Shipment(null, orderId, receipt.shipmentId(),
                    receipt.trackingCode(), key, ShipmentStatus.CREATED, LocalDateTime.now()));
            if (result.created()) {
                audit.record(command.actorType(), command.actorId(), "SHIPMENT_REQUEST", "ORDER", orderId,
                        AuditOutcome.SUCCESS, Map.of("idempotencyKey", key, "shipmentId", receipt.shipmentId(),
                                "trackingCode", receipt.trackingCode()));
            }
            return result;
        });
        log.info("Envío creado orderId={} created={}", orderId, insertion.created());
        return new ShipmentResult(insertion.shipment(), insertion.created());
    }
}
