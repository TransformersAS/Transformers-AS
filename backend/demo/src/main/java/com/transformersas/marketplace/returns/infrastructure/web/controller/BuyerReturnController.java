package com.transformersas.marketplace.returns.infrastructure.web.controller;

import com.transformersas.marketplace.returns.application.ReturnBuyerAccess;
import com.transformersas.marketplace.returns.application.ReturnException;
import com.transformersas.marketplace.returns.application.dto.ReturnViews;
import com.transformersas.marketplace.returns.application.usecase.RequestReturnUseCase;
import com.transformersas.marketplace.returns.application.usecase.RequestReturnUseCase.EvidenceUpload;
import com.transformersas.marketplace.returns.application.usecase.ReturnDecisionUseCase;
import com.transformersas.marketplace.returns.application.usecase.ReturnMethodSelectionUseCase;
import com.transformersas.marketplace.returns.application.usecase.ReturnQueryService;
import com.transformersas.marketplace.returns.infrastructure.web.request.ReturnRequests;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Devoluciones del comprador (CU-19): ver qué puede devolver, solicitar (con o sin imágenes), seguir la línea de tiempo y
 * responder al vendedor. Adaptador HTTP delgado: cada endpoint delega en un caso de uso y solo el rol activo COMPRADOR
 * entra (ReturnBuyerAccess). Ruta {@code /api/return-requests}, distinta del seguimiento logístico de CU-25 en
 * {@code /api/returns}.
 */
@RestController
@RequestMapping("/api/return-requests")
public class BuyerReturnController {
    private final ReturnBuyerAccess access;
    private final RequestReturnUseCase request;
    private final ReturnQueryService queries;
    private final ReturnDecisionUseCase decisions;
    private final ReturnMethodSelectionUseCase methodSelection;

    public BuyerReturnController(ReturnBuyerAccess access, RequestReturnUseCase request, ReturnQueryService queries,
                                 ReturnDecisionUseCase decisions, ReturnMethodSelectionUseCase methodSelection) {
        this.access = access;
        this.request = request;
        this.queries = queries;
        this.decisions = decisions;
        this.methodSelection = methodSelection;
    }

    /** Pedidos entregados del comprador, con cada línea y si se puede devolver o por qué no (RF-048). */
    @GetMapping("/eligible-orders")
    public List<ReturnViews.EligibleOrder> eligibleOrders(Authentication authentication) {
        return queries.eligibleOrders(access.require(authentication));
    }

    @GetMapping
    public List<ReturnViews.Summary> mine(Authentication authentication) {
        return queries.buyerReturns(access.require(authentication));
    }

    @GetMapping("/{id}")
    public ReturnViews.Detail detail(Authentication authentication, @PathVariable Long id) {
        return queries.buyerReturn(access.require(authentication), id);
    }

    /** Solicita sin imágenes. 201 si es nueva; 200 con la existente si esa línea ya tenía una solicitud (A3). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReturnViews.Requested> requestJson(Authentication authentication,
                                                             @RequestBody ReturnRequests.Request body) {
        return respond(request.execute(new RequestReturnUseCase.Command(access.require(authentication),
                body.orderId(), body.orderItemId(), body.reason(), body.description(), List.of())));
    }

    /** Solicita con hasta 3 imágenes: campos de formulario y las imágenes en {@code evidences}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReturnViews.Requested> requestMultipart(
            Authentication authentication,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long orderItemId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String description,
            @RequestParam(name = "evidences", required = false) List<MultipartFile> evidences) {
        Long buyer = access.require(authentication);
        List<EvidenceUpload> uploads = evidences == null ? List.of() : evidences.stream()
                // Un formulario sin archivo elegido envía una parte vacía y sin nombre: no es una imagen.
                .filter(file -> !(file.isEmpty() && (file.getOriginalFilename() == null
                        || file.getOriginalFilename().isBlank())))
                .map(BuyerReturnController::upload).toList();
        return respond(request.execute(new RequestReturnUseCase.Command(buyer, orderId, orderItemId, reason,
                description, uploads)));
    }

    /** Responde la solicitud de información del vendedor dentro de las 24 h (RF-052). */
    @PostMapping("/{id}/information-response")
    public ReturnViews.Detail answer(Authentication authentication, @PathVariable Long id,
                                     @RequestBody ReturnRequests.Answer body) {
        Long buyer = access.require(authentication);
        decisions.answerInformation(buyer, id, body.text());
        return queries.buyerReturn(buyer, id);
    }

    /** Los métodos de retorno que logística ofrece ahora para una devolución aprobada (RF-109). */
    @GetMapping("/{id}/return-methods")
    public List<ReturnViews.Method> returnMethods(Authentication authentication, @PathVariable Long id) {
        return methodSelection.methods(access.require(authentication), id);
    }

    /**
     * Elige el método de retorno: crea el retorno en logística y registra su seguimiento. Idempotente; si logística no
     * responde responde 503 y la devolución sigue aprobada para reintentar.
     */
    @PostMapping("/{id}/return-method")
    public ReturnViews.Detail chooseReturnMethod(Authentication authentication, @PathVariable Long id,
                                                 @RequestBody ReturnRequests.MethodChoice body) {
        Long buyer = access.require(authentication);
        methodSelection.choose(buyer, id, body.method());
        return queries.buyerReturn(buyer, id);
    }

    /** Una imagen de la solicitud, con ETag y {@code Cache-Control: private, no-cache} también en el 304. */
    @GetMapping("/{id}/evidences/{ordinal}")
    public ResponseEntity<byte[]> evidence(WebRequest webRequest, Authentication authentication,
                                           @PathVariable Long id, @PathVariable int ordinal) {
        return ReturnEvidenceResponses.serve(webRequest,
                queries.buyerEvidence(access.require(authentication), id, ordinal));
    }

    private static ResponseEntity<ReturnViews.Requested> respond(ReturnViews.Requested body) {
        return ResponseEntity.status(body.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    private static EvidenceUpload upload(MultipartFile file) {
        try {
            return new EvidenceUpload(file.getOriginalFilename(), file.getBytes());
        } catch (IOException unreadable) {
            throw ReturnException.field("evidences", "RETURN_EVIDENCE_UNREADABLE",
                    "No se pudo leer una de las imágenes");
        }
    }
}
