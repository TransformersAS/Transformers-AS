package com.transformersas.marketplace.restrictions.domain.repository;

import com.transformersas.marketplace.restrictions.domain.model.AccountInformationRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountInformationRequestRepository {

    Long insert(AccountInformationRequest request);

    Optional<AccountInformationRequest> findById(Long id);

    /** La solicitud abierta de la cuenta, si la hay (a lo sumo una, A3). */
    Optional<AccountInformationRequest> findOpenByAccount(Long accountId);

    /** Historia de solicitudes de la cuenta, más recientes primero. */
    List<AccountInformationRequest> findByAccount(Long accountId);

    /** Marca como vencida una solicitud abierta cuyo plazo ya pasó, para poder pedir otra (A4). */
    void expire(Long id);

    /** Registra la respuesta si sigue abierta. Devuelve false si ya tenía respuesta. */
    boolean answer(Long id, String response, LocalDateTime respondedAt);
}
