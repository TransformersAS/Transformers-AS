package com.transformersas.marketplace.returns.infrastructure.integration;

import com.transformersas.marketplace.claims.ClaimService;
import com.transformersas.marketplace.returns.domain.port.ClaimOpener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Abre la reclamación con el servicio de CU-13, a nombre del comprador y sin evidencias por enlace (las imágenes de la
 * devolución ya están con ella). Participa de la transacción de quien lo llama: si esta falla, la reclamación no queda.
 */
@Component
class ClaimServiceClaimOpener implements ClaimOpener {
    private final ClaimService claims;

    ClaimServiceClaimOpener(ClaimService claims) {
        this.claims = claims;
    }

    @Override
    public Long open(Long buyerAccountId, Long orderId, Long productId, String description) {
        return claims.open(buyerAccountId, orderId, productId, description, List.of()).id();
    }
}
