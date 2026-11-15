package com.transformersas.marketplace.restrictions.infrastructure.web.request;

import com.transformersas.marketplace.shared.web.StrictJsonRequest;

/**
 * Cuerpos JSON de CU-22. Son estrictos: una propiedad desconocida responde 400, así un cliente no puede colar, por
 * ejemplo, un administrador distinto. Las reglas de cada campo las valida el dominio.
 */
public final class RestrictionRequests {

    private RestrictionRequests() {
    }

    public record Information(String message) implements StrictJsonRequest {
    }

    public record Answer(String response) implements StrictJsonRequest {
    }

    public record Restriction(String scope, String role, String reason, boolean permanent, Integer durationDays)
            implements StrictJsonRequest {
    }

    public record DurationChange(Integer durationDays, String justification) implements StrictJsonRequest {
    }

    public record PermanenceChange(Integer durationDays, String justification) implements StrictJsonRequest {
    }

    public record Withdraw(String reason) implements StrictJsonRequest {
    }
}
