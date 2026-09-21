package com.transformersas.marketplace.returns.application.usecase;

import com.transformersas.marketplace.returns.application.ReturnException;
import com.transformersas.marketplace.returns.application.ReturnRecorder;
import com.transformersas.marketplace.returns.application.dto.ReturnViews;
import com.transformersas.marketplace.returns.domain.eligibility.Ineligibility;
import com.transformersas.marketplace.returns.domain.eligibility.ReturnCandidate;
import com.transformersas.marketplace.returns.domain.eligibility.ReturnEligibility;
import com.transformersas.marketplace.returns.domain.model.ReturnLine;
import com.transformersas.marketplace.returns.domain.model.ReturnReason;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.port.OrderForReturn;
import com.transformersas.marketplace.returns.domain.port.OrderForReturnReader;
import com.transformersas.marketplace.returns.domain.port.ReturnEvidenceStorage;
import com.transformersas.marketplace.returns.domain.port.StoreReturnPolicyReader;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import com.transformersas.marketplace.returns.infrastructure.config.ReturnProperties;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.files.ImageValidator;
import com.transformersas.marketplace.shared.files.ValidatedImage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * El comprador solicita la devolución de una línea de un pedido entregado (RF-049, RF-050). Valida todo antes de
 * escribir: datos completos (A4), pedido del comprador, solicitud existente (A3), elegibilidad (A1) e imágenes (A2). Lo
 * que escribe (solicitud, imágenes, línea de tiempo, auditoría y aviso a la tienda) va en una sola transacción, así que
 * un error no deja nada a medias (A12).
 *
 * <p>Una línea ya solicitada, en el estado que sea, devuelve la solicitud existente y no crea otra (A3): también cubre la
 * carrera de dos peticiones iguales, porque UNIQUE(order_item_id) deja pasar solo una. Una línea Rechazada no admite una
 * nueva solicitud; se disputa por reclamación (CU-13).
 */
@Service
public class RequestReturnUseCase {
    /** Imagen adjunta tal como llega del cliente, antes de validarla. */
    public record EvidenceUpload(String fileName, byte[] data) {
    }

    public record Command(Long buyerAccountId, Long orderId, Long orderItemId, String reasonCode, String description,
                          List<EvidenceUpload> images) {
    }

    private record ValidatedEvidence(String fileName, ValidatedImage image, byte[] data) {
    }

    private final OrderForReturnReader orders;
    private final StoreReturnPolicyReader storePolicy;
    private final ReturnRequestRepository repository;
    private final ReturnEvidenceStorage evidenceStorage;
    private final ReturnEligibility eligibility;
    private final ReturnRecorder recorder;
    private final ReturnProperties properties;
    private final ImageValidator imageValidator;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public RequestReturnUseCase(OrderForReturnReader orders, StoreReturnPolicyReader storePolicy,
                                ReturnRequestRepository repository, ReturnEvidenceStorage evidenceStorage,
                                ReturnEligibility eligibility, ReturnRecorder recorder, ReturnProperties properties,
                                Clock clock, PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.storePolicy = storePolicy;
        this.repository = repository;
        this.evidenceStorage = evidenceStorage;
        this.eligibility = eligibility;
        this.recorder = recorder;
        this.properties = properties;
        this.imageValidator = new ImageValidator(properties.evidence().maxSize().toBytes(),
                ImageValidator.DEFAULT_MAX_SIDE, ImageValidator.DEFAULT_MAX_PIXELS);
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ReturnViews.Requested execute(Command command) {
        ReturnReason reason = parseReason(command.reasonCode());
        ReturnRequest.validateRequestText(reason, command.description());
        OrderForReturn order = orders.findForBuyer(command.orderId(), command.buyerAccountId())
                .orElseThrow(() -> ReturnException.notFound("RETURN_ORDER_NOT_FOUND", "El pedido no existe"));

        Optional<ReturnLine> line = order.line(command.orderItemId());
        if (line.isPresent()) {
            Optional<ReturnRequest> existing = repository.findByOrderItemId(command.orderItemId());
            if (existing.isPresent()) {
                return requested(existing.get(), true);
            }
        }
        int windowDays = storePolicy.returnWindowDays(order.storeId()).orElseThrow(
                () -> ReturnException.notFound("RETURN_STORE_NOT_FOUND", "La tienda del pedido no existe"));
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<Ineligibility> failure = eligibility.firstFailure(
                new ReturnCandidate(order, command.orderItemId(), windowDays, now));
        if (failure.isPresent()) {
            throw new ReturnException(HttpStatus.UNPROCESSABLE_ENTITY, failure.get().code(),
                    failure.get().message(), Map.of("orderItemId", command.orderItemId()));
        }
        List<ValidatedEvidence> images = validateImages(command.images());

        ReturnRequest request = ReturnRequest.request(order.orderId(), line.orElseThrow(), command.buyerAccountId(),
                order.storeId(), reason, command.description(), windowDays, order.deliveredAt(), now);
        return transaction.execute(status -> persist(request, images, now));
    }

    private ReturnViews.Requested persist(ReturnRequest request, List<ValidatedEvidence> images, LocalDateTime now) {
        ReturnRequestRepository.Insertion insertion = repository.insertIfAbsent(request);
        if (!insertion.created()) {
            return requested(insertion.request(), true);
        }
        int ordinal = 0;
        for (ValidatedEvidence image : images) {
            ordinal++;
            evidenceStorage.save(request.getId(), ordinal, image.fileName(), image.image().contentType(),
                    image.image().sha256(), image.data());
        }
        recorder.record(request, request.openingEvent(), now);
        recorder.notifyStore(request, "REQUESTED", "REQUESTED", "Nueva solicitud de devolución",
                "Recibiste una solicitud de devolución de " + request.getLine().productName() + " del pedido #"
                        + request.getOrderId() + ".");
        return new ReturnViews.Requested(request.getId(), request.getLine().orderItemId(), request.getStatus(), false,
                images.size(), request.getCreatedAt());
    }

    private ReturnViews.Requested requested(ReturnRequest request, boolean duplicate) {
        return new ReturnViews.Requested(request.getId(), request.getLine().orderItemId(), request.getStatus(),
                duplicate, evidenceStorage.summaries(request.getId()).size(), request.getCreatedAt());
    }

    private static ReturnReason parseReason(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return ReturnReason.fromCode(code).orElseThrow(() -> ReturnException.field("reason",
                "RETURN_REASON_INVALID", "El motivo elegido no está en la lista de motivos"));
    }

    private List<ValidatedEvidence> validateImages(List<EvidenceUpload> uploads) {
        List<EvidenceUpload> files = uploads == null ? List.of() : uploads;
        if (files.size() > properties.evidence().maxCount()) {
            throw ReturnException.field("evidences", "RETURN_TOO_MANY_IMAGES",
                    "Puedes adjuntar hasta " + properties.evidence().maxCount() + " imágenes");
        }
        List<ValidatedEvidence> validated = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            EvidenceUpload upload = files.get(i);
            try {
                ValidatedImage image = imageValidator.validate(upload.data());
                validated.add(new ValidatedEvidence(fileName(upload.fileName(), i + 1, image), image, upload.data()));
            } catch (BusinessException invalid) {
                throw new ReturnException(HttpStatus.BAD_REQUEST, invalid.code(),
                        "Imagen " + (i + 1) + ": " + invalid.getMessage(), Map.of("field", "evidences[" + i + "]"));
            }
        }
        return validated;
    }

    /** Solo el nombre del archivo, sin rutas ni caracteres de control, y nunca vacío. */
    static String fileName(String original, int position, ValidatedImage image) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            name = "evidencia-" + position + ("image/png".equals(image.contentType()) ? ".png" : ".jpg");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
