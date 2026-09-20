package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.TrackingConflictException;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import com.transformersas.marketplace.logistics.domain.repository.OrderTrackingPort;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.shared.audit.ActorType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica al pedido una actualización de transporte informada por el servicio logístico (CU-24, RF-116, RF-118, RF-119).
 * Implementa el puerto de logística: es el único código que mueve un pedido a Recogido, En camino, Novedad de entrega,
 * Intento de entrega fallido, Entregado o Retornado al vendedor; ni el comprador ni el vendedor pueden hacerlo (A8).
 *
 * <p>Corre dentro de la transacción de quien procesa la actualización (MANDATORY): compare-and-set del estado,
 * historial, auditoría y notificaciones se confirman o se revierten juntos. Nunca retrocede el estado (A6): una
 * actualización que no es un avance válido devuelve OUT_OF_ORDER y no toca el pedido.
 */
@Service
public class ApplyLogisticsUpdateUseCase implements OrderTrackingPort {

    private static final Logger log = LoggerFactory.getLogger(ApplyLogisticsUpdateUseCase.class);

    private final OrderRepository orders;
    private final OrderChangeRecorder changes;

    public ApplyLogisticsUpdateUseCase(OrderRepository orders, OrderChangeRecorder changes) {
        this.orders = orders;
        this.changes = changes;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TrackingOutcome apply(Long orderId, TrackingUpdate update) {
        Order order = orders.findById(orderId).orElseThrow(() ->
                new IllegalStateException("El envío referencia un pedido inexistente: " + orderId));
        OrderStatus current = order.status();
        ShipmentEventType type = update.type();

        if (type == ShipmentEventType.NEXT_ATTEMPT_SCHEDULED) {
            // A3: solo informa que habrá otro intento; el estado cambia cuando logística informe el siguiente.
            return current == OrderStatus.DELIVERY_ATTEMPT_FAILED || current == OrderStatus.DELIVERY_EXCEPTION
                    ? TrackingOutcome.RECORDED : TrackingOutcome.OUT_OF_ORDER;
        }

        OrderStatus target = OrderStatus.valueOf(type.name());
        if (target == current && (target == OrderStatus.PICKED_UP || target == OrderStatus.IN_TRANSIT)) {
            // Pasos 8 y 10: más información de seguimiento del mismo estado, sin cambio ni aviso.
            return TrackingOutcome.RECORDED;
        }
        if (!current.allowedLogisticsTargets().contains(target)) {
            log.warn("Actualización logística fuera de orden orderId={} estado={} tipo={}", orderId, current, type);
            return TrackingOutcome.OUT_OF_ORDER;
        }

        // Compare-and-set: solo gana quien encuentra el pedido todavía en el estado que se leyó.
        if (!orders.transitionStatus(orderId, current, target)) {
            throw new TrackingConflictException("El pedido", orderId);
        }
        changes.recordTransition(orderId, current, target, ActorType.LOGISTICS, null, reason(update));
        notifyParties(order, target, update.eventId());
        log.info("Pedido actualizado por logística orderId={} {} -> {}", orderId, current, target);
        return TrackingOutcome.APPLIED;
    }

    private static String reason(TrackingUpdate update) {
        String detail = update.description() == null ? "" : ": " + update.description();
        String reason = "Actualización logística" + detail;
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    /** El comprador recibe todos los avisos; la tienda solo los que le importan. Sin datos personales en el texto. */
    private void notifyParties(Order order, OrderStatus target, String eventId) {
        String number = "#" + order.id();
        switch (target) {
            case PICKED_UP -> changes.notifyBuyer(order, "PICKED_UP", eventId, "Tu pedido fue recogido",
                    "El servicio logístico recogió tu pedido " + number + ".");
            case IN_TRANSIT -> changes.notifyBuyer(order, "IN_TRANSIT", eventId, "Tu pedido está en camino",
                    "Tu pedido " + number + " va en camino a su destino.");
            case DELIVERY_EXCEPTION -> {
                changes.notifyBuyer(order, "DELIVERY_EXCEPTION", eventId, "Novedad en la entrega de tu pedido",
                        "Hubo una novedad con la entrega de tu pedido " + number + "; consulta el seguimiento.");
                changes.notifyStore(order, "DELIVERY_EXCEPTION", eventId, "Novedad en la entrega",
                        "El servicio logístico informó una novedad en la entrega del pedido " + number + ".");
            }
            case DELIVERY_ATTEMPT_FAILED -> {
                changes.notifyBuyer(order, "DELIVERY_ATTEMPT_FAILED", eventId, "Intento de entrega fallido",
                        "No se pudo entregar tu pedido " + number + "; consulta el seguimiento.");
                changes.notifyStore(order, "DELIVERY_ATTEMPT_FAILED", eventId, "Intento de entrega fallido",
                        "El servicio logístico no pudo entregar el pedido " + number + ".");
            }
            case DELIVERED -> {
                changes.notifyBuyer(order, "DELIVERED", eventId, "Tu pedido fue entregado",
                        "Tu pedido " + number + " fue entregado.");
                changes.notifyStore(order, "DELIVERED", eventId, "Pedido entregado",
                        "El pedido " + number + " fue entregado al comprador.");
            }
            case RETURNED_TO_SELLER -> {
                changes.notifyBuyer(order, "RETURNED_TO_SELLER", eventId, "Tu pedido regresó al vendedor",
                        "No fue posible entregar tu pedido " + number + " y regresó al vendedor.");
                changes.notifyStore(order, "RETURNED_TO_SELLER", eventId, "Pedido retornado",
                        "El pedido " + number + " regresó a la tienda sin entregarse.");
            }
            default -> throw new IllegalStateException("Estado sin aviso logístico: " + target);
        }
    }
}
