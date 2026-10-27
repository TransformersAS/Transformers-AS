package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.auth.infrastructure.security.BuyerAccess;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/payments/mercadopago")
public class MercadoPagoController {
    private final MercadoPagoCheckoutService checkouts;
    private final MercadoPagoClient client;
    public MercadoPagoController(MercadoPagoCheckoutService checkouts, MercadoPagoClient client) {
        this.checkouts = checkouts; this.client = client;
    }
    @PostMapping("/checkouts")
    public MercadoPagoCheckoutService.CheckoutStatus start(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestBody MercadoPagoCheckoutService.Purchase request) {
        return checkouts.start(BuyerAccess.accountId(principal), request);
    }
    @GetMapping("/checkouts/{id}")
    public MercadoPagoCheckoutService.CheckoutStatus status(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String id) {
        return checkouts.status(BuyerAccess.accountId(principal), id);
    }
    public record Refresh(String paymentId) { }
    @PostMapping("/checkouts/{id}/refresh")
    public MercadoPagoCheckoutService.CheckoutStatus refresh(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String id, @RequestBody Refresh request) {
        return checkouts.reconcile(BuyerAccess.accountId(principal), id, request.paymentId());
    }
    @PostMapping("/webhook")
    public void webhook(@RequestParam(name = "data.id", required = false) String paymentId,
            @RequestHeader(name = "x-request-id", required = false) String requestId,
            @RequestHeader(name = "x-signature", required = false) String signature) {
        verify(client.webhookSecret(), paymentId, requestId, signature);
        checkouts.notification(paymentId);
    }
    static void verify(String secret, String id, String requestId, String signature) {
        if (secret == null || secret.isBlank()) throw new ResponseStatusException(SERVICE_UNAVAILABLE);
        try {
            if (id == null || !id.matches("[0-9]{1,30}") || requestId == null
                    || !requestId.matches("[a-zA-Z0-9-]{1,150}") || signature == null || signature.length() > 300)
                throw new IllegalArgumentException();
            String timestamp = null, digest = null;
            for (String part : signature.split(",")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals("ts")) timestamp = pair[1];
                if (pair.length == 2 && pair[0].equals("v1")) digest = pair[1];
            }
            if (timestamp == null || !timestamp.matches("[0-9]{10,16}") || digest == null || digest.length() != 64)
                throw new IllegalArgumentException();
            String manifest = "id:" + id + ";request-id:" + requestId + ";ts:" + timestamp + ";";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(digest)))
                throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new ResponseStatusException(UNAUTHORIZED, "Firma de Mercado Pago inválida");
        }
    }
}
