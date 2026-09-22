package com.transformersas.marketplace.returns.application.usecase;

import com.transformersas.marketplace.returns.application.ReturnException;
import com.transformersas.marketplace.returns.application.ReturnRecorder;
import com.transformersas.marketplace.returns.application.dto.ReturnViews;
import com.transformersas.marketplace.returns.domain.model.ReturnEvent;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.returns.domain.port.OrderForReturn;
import com.transformersas.marketplace.returns.domain.port.OrderForReturnReader;
import com.transformersas.marketplace.returns.domain.port.ReturnLogistics;
import com.transformersas.marketplace.returns.domain.port.ReturnShipmentRegistrar;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Cuando el vendedor aprueba, el comprador consulta los métodos de retorno disponibles y elige uno (RF-109, A7). Elegir
 * crea el retorno en logística con la clave {@code return-{id}} y deja registrado su seguimiento (CU-25).
 *
 * <p>La llamada a logística es externa y lenta, así que va <b>fuera de toda transacción de base de datos</b>: no se
 * mantienen bloqueos ni conexiones mientras se espera. Solo después se abre una transacción corta que bloquea la devolución,
 * guarda el método elegido, registra el seguimiento, la línea de tiempo y el aviso, todo junto. Por eso:
 * <ul>
 *   <li>si logística no responde, la devolución sigue Aprobada, sin método elegido, y se puede reintentar;</li>
 *   <li>si el retorno se creó en logística pero el registro local falla, todo lo local se revierte y reintentar con la misma
 *   clave no crea otro retorno: logística devuelve el mismo;</li>
 *   <li>si el método elegido ya no está disponible, se responde con un error claro y se pide elegir otro.</li>
 * </ul>
 * No hay plazo para elegir: la marca "atrasada" la calcula la consulta. Elegir el mismo método de nuevo no cambia nada; una
 * vez elegido no se puede cambiar, porque el retorno ya existe en logística.
 */
@Service
public class ReturnMethodSelectionUseCase {
    private final ReturnRequestRepository repository;
    private final OrderForReturnReader orders;
    private final ReturnLogistics logistics;
    private final ReturnShipmentRegistrar registrar;
    private final ReturnRecorder recorder;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ReturnMethodSelectionUseCase(ReturnRequestRepository repository, OrderForReturnReader orders,
                                        ReturnLogistics logistics, ReturnShipmentRegistrar registrar,
                                        ReturnRecorder recorder, Clock clock,
                                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.orders = orders;
        this.logistics = logistics;
        this.registrar = registrar;
        this.recorder = recorder;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Los métodos que logística ofrece ahora para esta devolución aprobada. */
    public List<ReturnViews.Method> methods(Long buyerAccountId, Long returnId) {
        ReturnRequest request = ownedByBuyer(buyerAccountId, returnId);
        requireApproved(request);
        if (request.getReturnMethodCode() != null) {
            throw alreadyChosen();
        }
        try {
            return logistics.methods(request.getOrderId(), request.getStoreId()).stream()
                    .map(method -> new ReturnViews.Method(method.code(), method.label())).toList();
        } catch (ReturnLogistics.UnavailableException unavailable) {
            throw unavailable();
        } catch (ReturnLogistics.RejectedException rejected) {
            throw new ReturnException(HttpStatus.BAD_GATEWAY, "RETURN_LOGISTICS_REJECTED",
                    "El servicio logístico no pudo darnos los métodos de retorno; inténtalo más tarde");
        }
    }

    /** Elige el método: crea el retorno en logística y registra su seguimiento. Idempotente y reintentable. */
    public void choose(Long buyerAccountId, Long returnId, String methodCode) {
        if (methodCode == null || methodCode.isBlank()) {
            throw ReturnException.field("method", "RETURN_METHOD_REQUIRED", "Elige un método de retorno");
        }
        String code = methodCode.strip();
        ReturnRequest request = ownedByBuyer(buyerAccountId, returnId);
        if (request.getReturnMethodCode() != null) {
            if (request.getReturnMethodCode().equals(code)) {
                return; // ya estaba elegido: no cambia nada ni vuelve a llamar a logística
            }
            throw alreadyChosen();
        }
        requireApproved(request);
        OrderForReturn order = orders.findForBuyer(request.getOrderId(), buyerAccountId).orElseThrow(
                () -> ReturnException.notFound("RETURN_ORDER_NOT_FOUND", "El pedido de la devolución no existe"));
        if (order.pickup() == null) {
            throw new ReturnException(HttpStatus.CONFLICT, "RETURN_PICKUP_ADDRESS_MISSING",
                    "El pedido no conserva la dirección de recogida");
        }

        // Fuera de toda transacción: llamada externa. Es idempotente por "return-{id}".
        ReturnLogistics.Receipt receipt;
        try {
            receipt = logistics.createReturn(new ReturnLogistics.Shipment(returnId, request.getOrderId(),
                    request.getStoreId(), code, order.pickup(), request.getLine().productName(),
                    request.getLine().quantity()));
        } catch (ReturnLogistics.UnavailableException unavailable) {
            throw unavailable();
        } catch (ReturnLogistics.RejectedException rejected) {
            throw new ReturnException(HttpStatus.CONFLICT, "RETURN_METHOD_UNAVAILABLE",
                    "El método de retorno elegido ya no está disponible; elige otro", Map.of("field", "method"));
        }

        transaction.executeWithoutResult(status -> save(buyerAccountId, returnId, code, receipt));
    }

    /** La parte local, en una sola transacción corta: si algo falla, no queda nada elegido ni registrado. */
    private void save(Long buyerAccountId, Long returnId, String code, ReturnLogistics.Receipt receipt) {
        ReturnRequest locked = repository.lockById(returnId).filter(found -> found.getBuyerAccountId().equals(buyerAccountId))
                .orElseThrow(ReturnQueryService::notFound);
        if (locked.getReturnMethodCode() != null) {
            if (locked.getReturnMethodCode().equals(code)) {
                return; // otra petición igual ya lo dejó registrado
            }
            throw alreadyChosen();
        }
        requireApproved(locked);
        LocalDateTime now = LocalDateTime.now(clock);
        ReturnEvent event = locked.chooseReturnMethod(buyerAccountId, code, now);
        repository.save(locked);
        registrar.register(returnId, buyerAccountId, locked.getStoreId(), receipt.providerReturnId(),
                receipt.trackingCode());
        recorder.record(locked, event, now);
        recorder.notifyStore(locked, "METHOD_CHOSEN", "METHOD_CHOSEN", "El comprador eligió cómo devolver",
                "El comprador eligió el método " + code + " para la devolución #" + returnId + ".");
    }

    private ReturnRequest ownedByBuyer(Long buyerAccountId, Long returnId) {
        return repository.findById(returnId).filter(found -> found.getBuyerAccountId().equals(buyerAccountId))
                .orElseThrow(ReturnQueryService::notFound);
    }

    private static void requireApproved(ReturnRequest request) {
        if (request.getStatus() != ReturnStatus.APPROVED) {
            throw BusinessException.conflict("RETURN_INVALID_STATE",
                    "Solo se elige el método de retorno de una devolución aprobada (estado " + request.getStatus() + ")");
        }
    }

    private static ReturnException alreadyChosen() {
        return new ReturnException(HttpStatus.CONFLICT, "RETURN_METHOD_ALREADY_CHOSEN",
                "Ya elegiste el método de retorno de esta devolución");
    }

    private static ReturnException unavailable() {
        return new ReturnException(HttpStatus.SERVICE_UNAVAILABLE, "RETURN_LOGISTICS_UNAVAILABLE",
                "El servicio logístico no está disponible en este momento. Tu devolución sigue aprobada: inténtalo de "
                        + "nuevo en unos minutos");
    }
}
