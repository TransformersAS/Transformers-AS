package com.transformersas.marketplace.logistics.infrastructure.web.controller;

import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Autentica al servicio logístico que llama al webhook (RNF-003): no tiene sesión, así que firma cada cuerpo con
 * HMAC-SHA256 usando el secreto compartido logistics.webhook.secret y envía "X-Logistics-Signature: sha256=hex".
 * Sin secreto configurado el webhook queda cerrado: nunca acepta actualizaciones sin autenticar. La comparación es en
 * tiempo constante. Las actualizaciones repetidas ya son inofensivas por su id de evento (RNF-043).
 */
@Component
public class WebhookSignatureVerifier {

    public static final String HEADER = "X-Logistics-Signature";
    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public WebhookSignatureVerifier(@Value("${logistics.webhook.secret:}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Lanza 401 con código estable si el webhook no está configurado o la firma no corresponde al cuerpo. */
    public void verify(byte[] body, String signatureHeader) {
        if (secret.length == 0) {
            throw BusinessException.unauthenticated("WEBHOOK_NOT_CONFIGURED",
                    "El webhook logístico no está habilitado: falta logistics.webhook.secret");
        }
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            throw invalid();
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()).strip());
        } catch (IllegalArgumentException notHex) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(sign(body), given)) {
            throw invalid();
        }
    }

    /** Firma que debe enviar el proveedor; pública para que las pruebas y el script de demostración la reproduzcan. */
    public String signature(byte[] body) {
        return PREFIX + HexFormat.of().formatHex(sign(body));
    }

    private byte[] sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 no está disponible", impossible);
        }
    }

    private static BusinessException invalid() {
        return BusinessException.unauthenticated("WEBHOOK_SIGNATURE_INVALID", "Firma del webhook inválida");
    }
}
