package com.transformersas.marketplace.suspensions.infrastructure.web.request;

import com.transformersas.marketplace.shared.web.StrictJsonRequest;

/**
 * Cuerpos JSON de CU-27. Son estrictos: una propiedad desconocida responde 400, así un cliente no puede colar, por
 * ejemplo, una fecha de fin o un administrador distinto. Las reglas de cada campo las valida el dominio.
 */
public final class SuspensionRequests {

    private SuspensionRequests() {
    }

    public record Clarification(String message) implements StrictJsonRequest {
    }

    public record Answer(String response) implements StrictJsonRequest {
    }

    public record NoActionReview(String note) implements StrictJsonRequest {
    }

    public record Suspension(String reason, Integer durationDays) implements StrictJsonRequest {
    }

    public record DurationChange(Integer durationDays, String justification) implements StrictJsonRequest {
    }

    public record Lift(String reason) implements StrictJsonRequest {
    }
}
