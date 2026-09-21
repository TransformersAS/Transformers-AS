package com.transformersas.marketplace.returns.domain.model;

import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.error.BusinessException;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Una solicitud de devolución de una línea de pedido (CU-19) y sus transiciones. El agregado solo decide qué cambios de
 * estado son válidos y calcula plazos; quién puede pedirlos (el comprador o la dueña de la tienda) lo comprueba la
 * capa de aplicación. Cada transición devuelve el hecho de la línea de tiempo que la capa de aplicación guarda junto
 * con el cambio, y ninguna llama a otro módulo: el agregado no sabe de logística, pagos ni reclamaciones.
 *
 * <pre>
 * REQUESTED → IN_REVIEW → APPROVED → IN_INSPECTION → REFUND_PENDING → FINISHED
 *                ↓  ↑                    (24 h)         (reintentos)
 *          INFO_REQUIRED
 * REQUESTED | IN_REVIEW | INFO_REQUIRED (con plazo vencido) → REJECTED
 * </pre>
 */
@Getter
public final class ReturnRequest {
    public static final int TEXT_MAX = 1000;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    public record Decision(String note, Long decidedByAccountId, LocalDateTime decidedAt) {
    }

    public record ProblemReport(String description, LocalDateTime reportedAt, Long claimId) {
    }

    public record InformationAsked(InformationRequest request, ReturnEvent event) {
    }

    public record InformationAnswered(InformationRequest request, String text, LocalDateTime respondedAt,
                                      ReturnEvent event) {
    }

    /** Todos los datos de una solicitud, para reconstruirla desde la base. */
    public record Data(Long id, Long orderId, ReturnLine line, BigDecimal refundAmount, Long buyerAccountId, Long storeId, ReturnStatus status,
                       ReturnReason reason, String description, ReturnOrigin origin, Long originClaimId,
                       Integer returnWindowDays, LocalDateTime deliveredAt, Decision decision, String returnMethodCode,
                       LocalDateTime methodChosenAt, LocalDateTime inspectionStartedAt, LocalDateTime inspectionDueAt,
                       ProblemReport problem, int refundAttempts, LocalDateTime nextActionAt,
                       InformationRequest openInformation, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    private Long id;
    private final Long orderId;
    private final ReturnLine line;
    private BigDecimal refundAmount;
    private final Long buyerAccountId;
    private final Long storeId;
    private ReturnStatus status;
    private final ReturnReason reason;
    private final String description;
    private ReturnOrigin origin;
    private Long originClaimId;
    private final Integer returnWindowDays;
    private final LocalDateTime deliveredAt;
    private Decision decision;
    private String returnMethodCode;
    private LocalDateTime methodChosenAt;
    private LocalDateTime inspectionStartedAt;
    private LocalDateTime inspectionDueAt;
    private ProblemReport problem;
    private int refundAttempts;
    private LocalDateTime nextActionAt;
    private InformationRequest openInformation;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private ReturnRequest(Data d) {
        this.id = d.id();
        this.orderId = d.orderId();
        this.line = d.line();
        this.refundAmount = d.refundAmount();
        this.buyerAccountId = d.buyerAccountId();
        this.storeId = d.storeId();
        this.status = d.status();
        this.reason = d.reason();
        this.description = d.description();
        this.origin = d.origin();
        this.originClaimId = d.originClaimId();
        this.returnWindowDays = d.returnWindowDays();
        this.deliveredAt = d.deliveredAt();
        this.decision = d.decision();
        this.returnMethodCode = d.returnMethodCode();
        this.methodChosenAt = d.methodChosenAt();
        this.inspectionStartedAt = d.inspectionStartedAt();
        this.inspectionDueAt = d.inspectionDueAt();
        this.problem = d.problem();
        this.refundAttempts = d.refundAttempts();
        this.nextActionAt = d.nextActionAt();
        this.openInformation = d.openInformation();
        this.createdAt = d.createdAt();
        this.updatedAt = d.updatedAt();
    }

    // ---------- Creación ----------

    /**
     * Solicitud del comprador (RF-049). Valida el motivo y la descripción (A4); la elegibilidad (A1) y la duplicada (A3)
     * las decide antes la capa de aplicación. Copia el plazo de la tienda y la fecha de entrega con los que se evaluó (D6).
     */
    public static ReturnRequest request(Long orderId, ReturnLine line, Long buyerAccountId, Long storeId,
                                        ReturnReason reason, String description, int returnWindowDays,
                                        LocalDateTime deliveredAt, LocalDateTime now) {
        if (reason == null) {
            throw invalid("RETURN_REASON_REQUIRED", "reason", "Elige el motivo de la devolución");
        }
        String text = requiredText(description, "RETURN_DESCRIPTION_REQUIRED", "description",
                "Describe por qué devuelves el producto");
        return new ReturnRequest(new Data(null, orderId, line, line.refundAmount(), buyerAccountId, storeId,
                ReturnStatus.REQUESTED, reason,
                text, ReturnOrigin.BUYER, null, returnWindowDays, deliveredAt, null, null, null, null, null, null, 0,
                null, null, now, now));
    }

    /** Valida el motivo y la descripción de una solicitud sin crearla (A4), para hacerlo antes de consultar nada más. */
    public static void validateRequestText(ReturnReason reason, String description) {
        if (reason == null) {
            throw invalid("RETURN_REASON_REQUIRED", "reason", "Elige el motivo de la devolución");
        }
        requiredText(description, "RETURN_DESCRIPTION_REQUIRED", "description", "Describe por qué devuelves el producto");
    }

    /**
     * Devolución que nace de una reclamación (CU-13): Aprobada desde el principio, sin plazo y con la referencia a la
     * reclamación. No pasa por revisión: la reclamación ya la decidió.
     */
    public static ReturnRequest approvedFromClaim(Long orderId, ReturnLine line, Long buyerAccountId, Long storeId,
                                                  Long claimId, String description, BigDecimal agreedRefund,
                                                  LocalDateTime now) {
        if (claimId == null) {
            throw new IllegalArgumentException("Una devolución originada por reclamación necesita su reclamación");
        }
        return new ReturnRequest(new Data(null, orderId, line, claimRefund(line, agreedRefund), buyerAccountId, storeId,
                ReturnStatus.APPROVED,
                ReturnReason.OTHER, requiredText(description, "RETURN_DESCRIPTION_REQUIRED", "description",
                "Describe la devolución"), ReturnOrigin.CLAIM, claimId, null, null,
                new Decision("Originada por la reclamación " + claimId, null, now), null, null, null, null, null, 0,
                null, null, now, now));
    }

    /**
     * Una reclamación (CU-13) exige la devolución de una línea que el vendedor había rechazado: la reabre como Aprobada,
     * sin plazo, con el reembolso acordado en la reclamación (o el de la línea si no se acordó otro). Solo desde
     * Rechazada. Deja en la línea de tiempo la reapertura y el cambio de origen, y conserva todo lo anterior.
     */
    public java.util.List<ReturnEvent> reopenFromClaim(Long claimId, BigDecimal agreedRefund, LocalDateTime now) {
        if (claimId == null) {
            throw new IllegalArgumentException("Reabrir por reclamación necesita su reclamación");
        }
        requireStatus("reabrir por una reclamación", ReturnStatus.REJECTED);
        ReturnOrigin previousOrigin = this.origin;
        this.refundAmount = claimRefund(line, agreedRefund);
        this.origin = ReturnOrigin.CLAIM;
        this.originClaimId = claimId;
        this.decision = new Decision("Reabierta por la reclamación " + claimId, null, now);
        this.returnMethodCode = null;
        this.methodChosenAt = null;
        ReturnEvent reopened = move(ReturnStatus.APPROVED, ReturnEventType.REOPENED_FROM_CLAIM, ActorType.SYSTEM, null,
                "Reclamación " + claimId, now);
        ReturnEvent originChanged = new ReturnEvent(ReturnEventType.ORIGIN_CHANGED, null, null, ActorType.SYSTEM, null,
                previousOrigin + " → " + ReturnOrigin.CLAIM + " (reclamación " + claimId + ")");
        return java.util.List.of(reopened, originChanged);
    }

    /** El reembolso acordado en la reclamación; sin uno, el de la línea. Nunca más que la línea ni cero. */
    private static BigDecimal claimRefund(ReturnLine line, BigDecimal agreedRefund) {
        if (agreedRefund == null) {
            return line.refundAmount();
        }
        if (agreedRefund.signum() <= 0 || agreedRefund.compareTo(line.refundAmount()) > 0) {
            throw invalid("RETURN_REFUND_AMOUNT_INVALID", "refund",
                    "El reembolso debe ser mayor que cero y no superar lo pagado por la línea");
        }
        return agreedRefund;
    }

    public static ReturnRequest restore(Data data) {
        return new ReturnRequest(data);
    }

    /** Asigna el id que generó la base al guardarla. */
    public void assignId(Long newId) {
        if (this.id != null) {
            throw new IllegalStateException("La devolución ya tiene id");
        }
        this.id = newId;
    }

    /** El hecho con el que empieza la línea de tiempo. */
    public ReturnEvent openingEvent() {
        if (origin == ReturnOrigin.CLAIM) {
            return new ReturnEvent(ReturnEventType.APPROVED_FROM_CLAIM, null, ReturnStatus.APPROVED, ActorType.SYSTEM,
                    null, "Reclamación " + originClaimId);
        }
        return new ReturnEvent(ReturnEventType.REQUESTED, null, ReturnStatus.REQUESTED, ActorType.BUYER,
                buyerAccountId, null);
    }

    // ---------- Vendedor: revisar y decidir ----------

    /** El vendedor abre la solicitud (RF-106). */
    public ReturnEvent startReview(Long sellerAccountId, LocalDateTime now) {
        requireStatus("abrir la revisión", ReturnStatus.REQUESTED);
        return move(ReturnStatus.IN_REVIEW, ReturnEventType.REVIEW_STARTED, ActorType.SELLER, sellerAccountId, null,
                now);
    }

    /** Pide información al comprador y le abre el plazo de respuesta (RF-052, RF-107). */
    public InformationAsked requestInformation(Long sellerAccountId, String message, LocalDateTime now,
                                               Duration window) {
        requireStatus("pedir información", ReturnStatus.IN_REVIEW);
        String text = requiredText(message, "RETURN_MESSAGE_REQUIRED", "message", "Escribe qué información necesitas");
        InformationRequest request = new InformationRequest(null, text, sellerAccountId, now, now.plus(window));
        this.openInformation = request;
        return new InformationAsked(request, move(ReturnStatus.INFO_REQUIRED, ReturnEventType.INFORMATION_REQUESTED,
                ActorType.SELLER, sellerAccountId, "Plazo hasta " + request.dueAt(), now));
    }

    /** El comprador responde dentro del plazo; la solicitud vuelve a revisión. */
    public InformationAnswered answerInformation(Long buyerAccountId, String text, LocalDateTime now) {
        requireStatus("responder", ReturnStatus.INFO_REQUIRED);
        if (openInformation == null) {
            throw conflict("RETURN_INVALID_STATE", "No hay una solicitud de información abierta");
        }
        if (openInformation.isExpired(now)) {
            throw conflict("RETURN_INFORMATION_EXPIRED",
                    "El plazo de 24 horas para responder venció; el vendedor puede rechazar la devolución");
        }
        String answer = requiredText(text, "RETURN_RESPONSE_REQUIRED", "text", "Escribe tu respuesta");
        InformationRequest answered = openInformation;
        this.openInformation = null;
        return new InformationAnswered(answered, answer, now, move(ReturnStatus.IN_REVIEW,
                ReturnEventType.INFORMATION_ANSWERED, ActorType.BUYER, buyerAccountId, null, now));
    }

    /**
     * Rechaza con justificación (RF-108). Desde Información requerida solo si el comprador no respondió a tiempo (A5).
     */
    public ReturnEvent reject(Long sellerAccountId, String note, LocalDateTime now) {
        requireStatus("rechazar", ReturnStatus.REQUESTED, ReturnStatus.IN_REVIEW, ReturnStatus.INFO_REQUIRED);
        if (status == ReturnStatus.INFO_REQUIRED && !isInformationExpired(now)) {
            throw conflict("RETURN_INFORMATION_PENDING",
                    "Solo puedes rechazar cuando el comprador no responde dentro del plazo de 24 horas");
        }
        String justification = requiredText(note, "RETURN_JUSTIFICATION_REQUIRED", "note",
                "Escribe la justificación del rechazo");
        this.decision = new Decision(justification, sellerAccountId, now);
        this.openInformation = null;
        return move(ReturnStatus.REJECTED, ReturnEventType.REJECTED, ActorType.SELLER, sellerAccountId, justification,
                now);
    }

    /** Aprueba. La justificación es opcional; el retorno logístico lo organiza la capa de aplicación (RF-109). */
    public ReturnEvent approve(Long sellerAccountId, String note, LocalDateTime now) {
        requireStatus("aprobar", ReturnStatus.IN_REVIEW);
        String justification = optionalText(note);
        this.decision = new Decision(justification, sellerAccountId, now);
        return move(ReturnStatus.APPROVED, ReturnEventType.APPROVED, ActorType.SELLER, sellerAccountId, justification,
                now);
    }

    // ---------- Comprador: elegir el método de retorno ----------

    /** Elige (o cambia, A7) el método de retorno; no cambia el estado. */
    public ReturnEvent chooseReturnMethod(Long buyerAccountId, String methodCode, LocalDateTime now) {
        requireStatus("elegir el método de retorno", ReturnStatus.APPROVED);
        if (methodCode == null || methodCode.isBlank()) {
            throw invalid("RETURN_METHOD_REQUIRED", "method", "Elige un método de retorno");
        }
        this.returnMethodCode = methodCode.strip();
        this.methodChosenAt = now;
        this.updatedAt = now;
        return new ReturnEvent(ReturnEventType.METHOD_CHOSEN, null, null, ActorType.BUYER, buyerAccountId,
                returnMethodCode);
    }

    // ---------- Inspección y reembolso ----------

    /**
     * Logística confirmó la entrega al vendedor: abre la ventana de inspección. Idempotente (A10): un evento repetido, o
     * uno que llega cuando la devolución ya avanzó, no cambia nada y devuelve vacío.
     */
    public Optional<ReturnEvent> startInspection(LocalDateTime now, Duration window) {
        if (status != ReturnStatus.APPROVED) {
            if (status == ReturnStatus.IN_INSPECTION || status == ReturnStatus.REFUND_PENDING
                    || status == ReturnStatus.FINISHED) {
                return Optional.empty();
            }
            throw conflict("RETURN_INVALID_STATE", "La mercancía no puede llegar a una devolución en estado " + status);
        }
        this.inspectionStartedAt = now;
        this.inspectionDueAt = now.plus(window);
        this.nextActionAt = inspectionDueAt;
        return Optional.of(move(ReturnStatus.IN_INSPECTION, ReturnEventType.INSPECTION_STARTED, ActorType.SYSTEM, null,
                "Ventana hasta " + inspectionDueAt, now));
    }

    /**
     * El vendedor reporta un problema dentro de la ventana (A8, RF-053). El estado sigue En inspección, se detiene el
     * reembolso automático (deja de haber próxima acción) y queda la reclamación abierta a nombre del comprador.
     */
    public ReturnEvent reportProblem(Long sellerAccountId, String problemDescription, Long claimId,
                                     LocalDateTime now) {
        requireStatus("reportar un problema", ReturnStatus.IN_INSPECTION);
        if (problem != null) {
            throw conflict("RETURN_PROBLEM_ALREADY_REPORTED", "Ya reportaste un problema con esta devolución");
        }
        if (now.isAfter(inspectionDueAt)) {
            throw conflict("RETURN_INSPECTION_CLOSED", "La ventana de inspección de 24 horas ya terminó");
        }
        String text = requiredText(problemDescription, "RETURN_PROBLEM_REQUIRED", "description",
                "Describe el problema encontrado");
        this.problem = new ProblemReport(text, now, claimId);
        this.nextActionAt = null;
        this.updatedAt = now;
        return new ReturnEvent(ReturnEventType.PROBLEM_REPORTED, null, null, ActorType.SELLER, sellerAccountId,
                claimId == null ? null : "Reclamación " + claimId);
    }

    /**
     * Comprueba que se puede reportar un problema ahora (estado, ventana y que no haya uno ya) sin cambiar nada, para
     * hacerlo antes de abrir la reclamación que lo acompaña.
     */
    public void ensureCanReportProblem(LocalDateTime now) {
        requireStatus("reportar un problema", ReturnStatus.IN_INSPECTION);
        if (problem != null) {
            throw conflict("RETURN_PROBLEM_ALREADY_REPORTED", "Ya reportaste un problema con esta devolución");
        }
        if (now.isAfter(inspectionDueAt)) {
            throw conflict("RETURN_INSPECTION_CLOSED", "La ventana de inspección de 24 horas ya terminó");
        }
    }

    /** Terminó la ventana sin problema: pasa a Reembolso pendiente y queda lista para el primer intento. */
    public ReturnEvent beginRefund(LocalDateTime now) {
        requireStatus("iniciar el reembolso", ReturnStatus.IN_INSPECTION);
        if (problem != null) {
            throw conflict("RETURN_PROBLEM_REPORTED",
                    "Hay un problema reportado: el reembolso se decide por la reclamación");
        }
        if (now.isBefore(inspectionDueAt)) {
            throw conflict("RETURN_INSPECTION_OPEN", "La ventana de inspección todavía no termina");
        }
        this.refundAttempts = 0;
        this.nextActionAt = now;
        return move(ReturnStatus.REFUND_PENDING, ReturnEventType.REFUND_REQUESTED, ActorType.SYSTEM, null,
                "return-" + id, now);
    }

    /**
     * El reembolso no quedó completado (falló o sigue pendiente en la pasarela, A9): cuenta el intento y agenda otro
     * con la misma clave, o lo deja para revisión manual al llegar al máximo. El estado sigue Reembolso pendiente.
     */
    public ReturnEvent refundNotCompleted(LocalDateTime now, String reason, ReturnPolicy policy) {
        requireStatus("reintentar el reembolso", ReturnStatus.REFUND_PENDING);
        this.refundAttempts++;
        this.updatedAt = now;
        String detail = reason == null ? null : reason.length() > 400 ? reason.substring(0, 400) : reason;
        if (refundAttempts >= policy.refundMaxAttempts()) {
            this.nextActionAt = null;
            return new ReturnEvent(ReturnEventType.REFUND_GAVE_UP, null, null, ActorType.SYSTEM, null, detail);
        }
        this.nextActionAt = now.plus(policy.refundRetryDelay());
        return new ReturnEvent(ReturnEventType.REFUND_RETRY_SCHEDULED, null, null, ActorType.SYSTEM, null, detail);
    }

    /** La pasarela confirmó el reembolso: la devolución termina. */
    public ReturnEvent refundCompleted(LocalDateTime now) {
        requireStatus("finalizar", ReturnStatus.REFUND_PENDING);
        this.nextActionAt = null;
        return move(ReturnStatus.FINISHED, ReturnEventType.FINISHED, ActorType.SYSTEM, null, "return-" + id, now);
    }

    /**
     * Reserva la devolución para quien la está procesando: aplaza su próxima acción para que otra réplica no la tome al
     * mismo tiempo. Si el proceso se cae, la acción vuelve a estar vencida cuando termina la reserva.
     */
    public void lease(LocalDateTime until, LocalDateTime now) {
        requireStatus("reservar", ReturnStatus.IN_INSPECTION, ReturnStatus.REFUND_PENDING);
        this.nextActionAt = until;
        this.updatedAt = now;
    }

    // ---------- Consultas derivadas (no se guardan) ----------

    /** Clave de idempotencia del reembolso de esta devolución (D4). */
    public String refundKey() {
        return "return-" + id;
    }

    public boolean isInformationExpired(LocalDateTime now) {
        return openInformation != null && openInformation.isExpired(now);
    }

    /** Sin decisión del vendedor pasado el plazo configurado; solo una marca, no decide nada (D2). */
    public boolean isSellerDecisionOverdue(LocalDateTime now, ReturnPolicy policy) {
        return (status == ReturnStatus.REQUESTED || status == ReturnStatus.IN_REVIEW)
                && now.isAfter(createdAt.plus(policy.sellerDecisionDeadline()));
    }

    /** Aprobada sin método de retorno elegido pasado el plazo configurado; solo una marca. */
    public boolean isMethodSelectionOverdue(LocalDateTime now, ReturnPolicy policy) {
        return status == ReturnStatus.APPROVED && returnMethodCode == null && decision != null
                && now.isAfter(decision.decidedAt().plus(policy.methodSelectionOverdueAfter()));
    }

    /** Fin del plazo de devolución con el que se evaluó; vacío si no aplica (origen CLAIM) o no había fecha. */
    public Optional<LocalDateTime> returnWindowEndsAt() {
        if (returnWindowDays == null || deliveredAt == null) {
            return Optional.empty();
        }
        return Optional.of(deliveredAt.plusDays(returnWindowDays));
    }

    public boolean hasProblemReported() {
        return problem != null;
    }

    // ---------- Internos ----------

    private ReturnEvent move(ReturnStatus to, ReturnEventType type, ActorType actorType, Long actorId, String details,
                             LocalDateTime now) {
        ReturnStatus from = this.status;
        this.status = to;
        this.updatedAt = now;
        if (to.isTerminal() || to == ReturnStatus.IN_REVIEW || to == ReturnStatus.APPROVED
                || to == ReturnStatus.INFO_REQUIRED) {
            this.nextActionAt = null;
        }
        return new ReturnEvent(type, from, to, actorType, actorId, details);
    }

    private void requireStatus(String action, ReturnStatus... allowed) {
        for (ReturnStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw conflict("RETURN_INVALID_STATE", "No se puede " + action + " una devolución en estado " + status);
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return cleaned(value, "note");
    }

    private static String requiredText(String value, String code, String field, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(code, field, message);
        }
        return cleaned(value, field);
    }

    private static String cleaned(String value, String field) {
        String text = value.strip();
        if (text.length() > TEXT_MAX) {
            throw invalid("RETURN_TEXT_TOO_LONG", field, "El texto admite hasta " + TEXT_MAX + " caracteres");
        }
        if (CONTROL.matcher(text).find()) {
            throw invalid("RETURN_TEXT_INVALID", field, "El texto contiene caracteres no válidos");
        }
        return text;
    }

    private static BusinessException invalid(String code, String field, String message) {
        return new BusinessException(BusinessException.Kind.INVALID, code, message, Map.of("field", field));
    }

    private static BusinessException conflict(String code, String message) {
        return BusinessException.conflict(code, message);
    }
}
