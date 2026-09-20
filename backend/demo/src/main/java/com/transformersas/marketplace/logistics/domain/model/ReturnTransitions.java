package com.transformersas.marketplace.logistics.domain.model;

import static com.transformersas.marketplace.logistics.domain.model.ReturnShipment.MAX_FAILED_PICKUPS;
import static com.transformersas.marketplace.logistics.domain.model.ReturnStatus.DELIVERED_TO_SELLER;
import static com.transformersas.marketplace.logistics.domain.model.ReturnStatus.IN_RETURN;
import static com.transformersas.marketplace.logistics.domain.model.ReturnStatus.LOGISTICS_ISSUE;
import static com.transformersas.marketplace.logistics.domain.model.ReturnStatus.PICKED_UP;
import static com.transformersas.marketplace.logistics.domain.model.ReturnStatus.PICKUP_FAILED;
import static com.transformersas.marketplace.logistics.domain.model.ReturnStatus.PICKUP_PENDING;

/**
 * Reglas de la máquina de estados del retorno (RF-110, A1 a A5), centralizadas para que ningún otro código decida
 * transiciones. Una actualización que retrocede el estado se rechaza (A5); una que repite el estado actual solo se
 * registra; una novedad puede resolverse hacia adelante (A3); el tercer intento de recogida fallido detiene las
 * recogidas automáticas (A2) y desde ahí ya no se acepta ningún cambio hasta que CU-19 gestione el caso.
 */
public final class ReturnTransitions {

    public enum Result { APPLY, RECORD, REJECT }

    /** Decisión: qué hacer con la actualización y cómo queda el seguimiento si se aplica. */
    public record Evaluation(Result result, ReturnStatus target, int failedPickups, boolean pickupStopped) {
    }

    private ReturnTransitions() {
    }

    public static Evaluation evaluate(ReturnShipment current, ReturnEventType type) {
        ReturnStatus status = current.status();
        if (status.isFinal() || current.pickupStopped()) {
            return reject(current);
        }
        boolean pickedUp = current.pickedUpAt() != null;
        return switch (type) {
            case PICKUP_SCHEDULED -> switch (status) {
                case PICKUP_FAILED -> apply(current, PICKUP_PENDING);
                case PICKUP_PENDING -> record(current);
                default -> reject(current);
            };
            case PICKED_UP -> switch (status) {
                case PICKUP_PENDING, PICKUP_FAILED -> apply(current, PICKED_UP);
                case LOGISTICS_ISSUE -> pickedUp ? reject(current) : apply(current, PICKED_UP);
                case PICKED_UP -> record(current);
                default -> reject(current);
            };
            case IN_TRANSIT -> switch (status) {
                case PICKUP_PENDING, PICKED_UP, LOGISTICS_ISSUE -> apply(current, IN_RETURN);
                case IN_RETURN -> record(current);
                default -> reject(current);
            };
            case INCIDENT -> switch (status) {
                case PICKUP_PENDING, PICKED_UP, IN_RETURN, LOGISTICS_ISSUE -> apply(current, LOGISTICS_ISSUE);
                default -> reject(current);
            };
            case PICKUP_FAILED -> switch (status) {
                case PICKUP_PENDING, PICKUP_FAILED -> failedPickup(current);
                case LOGISTICS_ISSUE -> pickedUp ? reject(current) : failedPickup(current);
                default -> reject(current);
            };
            case DELIVERED_TO_SELLER -> switch (status) {
                case PICKED_UP, IN_RETURN -> apply(current, DELIVERED_TO_SELLER);
                case LOGISTICS_ISSUE -> pickedUp ? apply(current, DELIVERED_TO_SELLER) : reject(current);
                default -> reject(current);
            };
        };
    }

    private static Evaluation apply(ReturnShipment current, ReturnStatus target) {
        return new Evaluation(Result.APPLY, target, current.failedPickups(), current.pickupStopped());
    }

    private static Evaluation record(ReturnShipment current) {
        return new Evaluation(Result.RECORD, current.status(), current.failedPickups(), current.pickupStopped());
    }

    private static Evaluation reject(ReturnShipment current) {
        return new Evaluation(Result.REJECT, current.status(), current.failedPickups(), current.pickupStopped());
    }

    private static Evaluation failedPickup(ReturnShipment current) {
        int failed = current.failedPickups() + 1;
        return new Evaluation(Result.APPLY, PICKUP_FAILED, failed, failed >= MAX_FAILED_PICKUPS);
    }
}
