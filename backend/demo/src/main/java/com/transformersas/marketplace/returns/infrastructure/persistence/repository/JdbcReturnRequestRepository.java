package com.transformersas.marketplace.returns.infrastructure.persistence.repository;

import com.transformersas.marketplace.returns.domain.model.InformationRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnEvent;
import com.transformersas.marketplace.returns.domain.model.ReturnEventType;
import com.transformersas.marketplace.returns.domain.model.ReturnLine;
import com.transformersas.marketplace.returns.domain.model.ReturnOrigin;
import com.transformersas.marketplace.returns.domain.model.ReturnReason;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import com.transformersas.marketplace.shared.audit.ActorType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Adaptador JDBC de las devoluciones. La unicidad por línea de pedido la garantiza UNIQUE(order_item_id). */
@Repository
class JdbcReturnRequestRepository implements ReturnRequestRepository {
    private static final int MAX_DETAILS = 500;
    private static final String COLUMNS = """
            id, order_id, order_item_id, product_id, product_name, quantity, unit_price, refund_amount,
            buyer_account_id, store_id, status, reason_code, description, origin, origin_claim_id, return_window_days,
            delivered_at, decision_note, decided_by_account_id, decided_at, return_method_code, method_chosen_at,
            inspection_started_at, inspection_due_at, problem_reported, problem_description, problem_reported_at,
            claim_id, refund_attempts, next_action_at, created_at, updated_at""";

    private final JdbcClient jdbc;

    JdbcReturnRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- Altas y lecturas ----------

    @Override
    public Insertion insertIfAbsent(ReturnRequest request) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                            INSERT INTO return_requests (order_id, order_item_id, product_id, product_name, quantity,
                                unit_price, refund_amount, buyer_account_id, store_id, status, reason_code, description,
                                origin, origin_claim_id, return_window_days, delivered_at, decision_note,
                                decided_by_account_id, decided_at, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(request.getOrderId(), request.getLine().orderItemId(), request.getLine().productId(),
                            request.getLine().productName(), request.getLine().quantity(),
                            request.getLine().unitPrice(), request.getRefundAmount(), request.getBuyerAccountId(),
                            request.getStoreId(), request.getStatus().name(), request.getReason().name(),
                            request.getDescription(), request.getOrigin().name(), request.getOriginClaimId(),
                            request.getReturnWindowDays(), request.getDeliveredAt(),
                            request.getDecision() == null ? null : request.getDecision().note(),
                            request.getDecision() == null ? null : request.getDecision().decidedByAccountId(),
                            request.getDecision() == null ? null : request.getDecision().decidedAt(),
                            request.getCreatedAt(), request.getUpdatedAt())
                    .update(keys);
            request.assignId(keys.getKey().longValue());
            return new Insertion(request, true);
        } catch (DuplicateKeyException duplicate) {
            return new Insertion(findByOrderItemId(request.getLine().orderItemId()).orElseThrow(() -> duplicate),
                    false);
        }
    }

    @Override
    public Optional<ReturnRequest> findById(Long id) {
        return one("SELECT " + COLUMNS + " FROM return_requests WHERE id = ?", id);
    }

    @Override
    public Optional<ReturnRequest> lockById(Long id) {
        return one("SELECT " + COLUMNS + " FROM return_requests WHERE id = ? FOR UPDATE", id);
    }

    @Override
    public Optional<ReturnRequest> findByOrderItemId(Long orderItemId) {
        return one("SELECT " + COLUMNS + " FROM return_requests WHERE order_item_id = ?", orderItemId);
    }

    @Override
    public Optional<ReturnRequest> lockByOrderItemId(Long orderItemId) {
        return one("SELECT " + COLUMNS + " FROM return_requests WHERE order_item_id = ? FOR UPDATE", orderItemId);
    }

    @Override
    public List<ReturnRequest> findByOrderItemIds(Collection<Long> orderItemIds) {
        if (orderItemIds.isEmpty()) {
            return List.of();
        }
        return hydrate(jdbc.sql("SELECT " + COLUMNS + " FROM return_requests WHERE order_item_id IN (:ids) ORDER BY id")
                .param("ids", orderItemIds).query((rs, n) -> row(rs)).list());
    }

    @Override
    public List<ReturnRequest> findByBuyer(Long buyerAccountId) {
        return hydrate(jdbc.sql("SELECT " + COLUMNS + " FROM return_requests WHERE buyer_account_id = ? "
                        + "ORDER BY id DESC").param(buyerAccountId).query((rs, n) -> row(rs)).list());
    }

    @Override
    public List<ReturnRequest> findByStore(Long storeId, ReturnStatus statusOrNull) {
        if (statusOrNull == null) {
            return hydrate(jdbc.sql("SELECT " + COLUMNS + " FROM return_requests WHERE store_id = ? ORDER BY id")
                    .param(storeId).query((rs, n) -> row(rs)).list());
        }
        return hydrate(jdbc.sql("SELECT " + COLUMNS + " FROM return_requests WHERE store_id = ? AND status = ? "
                        + "ORDER BY id").params(storeId, statusOrNull.name()).query((rs, n) -> row(rs)).list());
    }

    // ---------- Cambios ----------

    @Override
    public void save(ReturnRequest request) {
        ReturnRequest.Decision decision = request.getDecision();
        ReturnRequest.ProblemReport problem = request.getProblem();
        jdbc.sql("""
                        UPDATE return_requests SET status = ?, refund_amount = ?, origin = ?, origin_claim_id = ?,
                            decision_note = ?, decided_by_account_id = ?, decided_at = ?, return_method_code = ?,
                            method_chosen_at = ?, inspection_started_at = ?, inspection_due_at = ?,
                            problem_reported = ?, problem_description = ?, problem_reported_at = ?, claim_id = ?,
                            refund_attempts = ?, next_action_at = ?, updated_at = ?
                        WHERE id = ?""")
                .params(request.getStatus().name(), request.getRefundAmount(), request.getOrigin().name(),
                        request.getOriginClaimId(), decision == null ? null : decision.note(),
                        decision == null ? null : decision.decidedByAccountId(),
                        decision == null ? null : decision.decidedAt(), request.getReturnMethodCode(),
                        request.getMethodChosenAt(), request.getInspectionStartedAt(), request.getInspectionDueAt(),
                        problem != null, problem == null ? null : problem.description(),
                        problem == null ? null : problem.reportedAt(), problem == null ? null : problem.claimId(),
                        request.getRefundAttempts(), request.getNextActionAt(), request.getUpdatedAt(), request.getId())
                .update();
    }

    @Override
    public Long addInformationRequest(Long returnId, InformationRequest request) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO return_information_requests (return_id, message, requested_by_account_id,
                            requested_at, due_at, status) VALUES (?, ?, ?, ?, ?, 'OPEN')""")
                .params(returnId, request.message(), request.requestedByAccountId(), request.requestedAt(),
                        request.dueAt())
                .update(keys);
        return keys.getKey().longValue();
    }

    @Override
    public void answerInformationRequest(Long informationId, String text, LocalDateTime at) {
        jdbc.sql("""
                        UPDATE return_information_requests SET status = 'ANSWERED', open_key = NULL, response_text = ?,
                            responded_at = ? WHERE id = ? AND status = 'OPEN'""")
                .params(text, at, informationId).update();
    }

    @Override
    public List<InformationRecord> informationHistory(Long returnId) {
        return jdbc.sql("""
                        SELECT id, message, requested_at, due_at, status, response_text, responded_at
                        FROM return_information_requests WHERE return_id = ? ORDER BY id""").param(returnId)
                .query((rs, n) -> new InformationRecord(rs.getLong("id"), rs.getString("message"),
                        rs.getObject("requested_at", LocalDateTime.class), rs.getObject("due_at", LocalDateTime.class),
                        rs.getString("status"), rs.getString("response_text"),
                        rs.getObject("responded_at", LocalDateTime.class))).list();
    }

    @Override
    public void appendEvent(Long returnId, ReturnEvent event, String correlationId, LocalDateTime at) {
        String details = event.details() == null ? null : event.details().length() > MAX_DETAILS
                ? event.details().substring(0, MAX_DETAILS) : event.details();
        jdbc.sql("""
                        INSERT INTO return_events (return_id, event_type, from_status, to_status, actor_type, actor_id,
                            details, correlation_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(returnId, event.type().name(), event.from() == null ? null : event.from().name(),
                        event.to() == null ? null : event.to().name(), event.actorType().name(), event.actorId(),
                        details, correlationId, at)
                .update();
    }

    @Override
    public List<TimelineEntry> timeline(Long returnId) {
        return jdbc.sql("""
                        SELECT id, event_type, from_status, to_status, actor_type, actor_id, details, created_at
                        FROM return_events WHERE return_id = ? ORDER BY id""").param(returnId)
                .query((rs, n) -> new TimelineEntry(rs.getLong("id"), ReturnEventType.valueOf(rs.getString("event_type")),
                        status(rs.getString("from_status")), status(rs.getString("to_status")),
                        ActorType.valueOf(rs.getString("actor_type")), rs.getObject("actor_id", Long.class),
                        rs.getString("details"), rs.getObject("created_at", LocalDateTime.class))).list();
    }

    @Override
    public List<Long> lockDueIds(LocalDateTime now, int limit) {
        // Un estado por consulta: con IN (...) y ORDER BY, MySQL ordena después de leer y bloquea todas las filas
        // vencidas, no solo las del lote. Con la igualdad, el índice (status, next_action_at) entrega el orden y el
        // LIMIT corta el bloqueo.
        List<Due> due = new java.util.ArrayList<>();
        for (ReturnStatus status : List.of(ReturnStatus.IN_INSPECTION, ReturnStatus.REFUND_PENDING)) {
            due.addAll(jdbc.sql("""
                            SELECT id, next_action_at FROM return_requests
                            WHERE status = ? AND next_action_at IS NOT NULL AND next_action_at <= ?
                            ORDER BY next_action_at, id LIMIT ? FOR UPDATE SKIP LOCKED""")
                    .params(status.name(), now, limit)
                    .query((rs, n) -> new Due(rs.getLong("id"), rs.getObject("next_action_at", LocalDateTime.class)))
                    .list());
        }
        return due.stream().sorted(java.util.Comparator.comparing(Due::at).thenComparing(Due::id)).limit(limit)
                .map(Due::id).toList();
    }

    private record Due(Long id, LocalDateTime at) {
    }

    // ---------- Mapeo ----------

    private record Row(ReturnRequest.Data data) {
    }

    private Optional<ReturnRequest> one(String sql, Object param) {
        return hydrate(jdbc.sql(sql).param(param).query((rs, n) -> row(rs)).list()).stream().findFirst();
    }

    /** Completa cada fila con su solicitud de información abierta, si tiene, en una sola consulta. */
    private List<ReturnRequest> hydrate(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, InformationRequest> open = new HashMap<>();
        jdbc.sql("""
                        SELECT id, return_id, message, requested_by_account_id, requested_at, due_at
                        FROM return_information_requests WHERE status = 'OPEN' AND return_id IN (:ids)""")
                .param("ids", rows.stream().map(row -> row.data().id()).toList())
                .query((rs, n) -> open.put(rs.getLong("return_id"), new InformationRequest(rs.getLong("id"),
                        rs.getString("message"), rs.getLong("requested_by_account_id"),
                        rs.getObject("requested_at", LocalDateTime.class), rs.getObject("due_at", LocalDateTime.class))))
                .list();
        return rows.stream().map(row -> {
            ReturnRequest.Data d = row.data();
            return ReturnRequest.restore(new ReturnRequest.Data(d.id(), d.orderId(), d.line(), d.refundAmount(),
                    d.buyerAccountId(), d.storeId(), d.status(), d.reason(), d.description(), d.origin(),
                    d.originClaimId(), d.returnWindowDays(), d.deliveredAt(), d.decision(), d.returnMethodCode(),
                    d.methodChosenAt(), d.inspectionStartedAt(), d.inspectionDueAt(), d.problem(), d.refundAttempts(),
                    d.nextActionAt(), open.get(d.id()), d.createdAt(), d.updatedAt()));
        }).toList();
    }

    private static Row row(ResultSet rs) throws SQLException {
        ReturnLine line = new ReturnLine(rs.getLong("order_item_id"), rs.getLong("product_id"),
                rs.getString("product_name"), rs.getInt("quantity"), rs.getObject("unit_price", BigDecimal.class));
        LocalDateTime decidedAt = rs.getObject("decided_at", LocalDateTime.class);
        ReturnRequest.Decision decision = decidedAt == null ? null : new ReturnRequest.Decision(
                rs.getString("decision_note"), rs.getObject("decided_by_account_id", Long.class), decidedAt);
        ReturnRequest.ProblemReport problem = rs.getBoolean("problem_reported") ? new ReturnRequest.ProblemReport(
                rs.getString("problem_description"), rs.getObject("problem_reported_at", LocalDateTime.class),
                rs.getObject("claim_id", Long.class)) : null;
        return new Row(new ReturnRequest.Data(rs.getLong("id"), rs.getLong("order_id"), line,
                rs.getObject("refund_amount", BigDecimal.class), rs.getLong("buyer_account_id"),
                rs.getLong("store_id"), ReturnStatus.valueOf(rs.getString("status")),
                ReturnReason.fromCode(rs.getString("reason_code")).orElse(ReturnReason.OTHER),
                rs.getString("description"), ReturnOrigin.valueOf(rs.getString("origin")),
                rs.getObject("origin_claim_id", Long.class), rs.getObject("return_window_days", Integer.class),
                rs.getObject("delivered_at", LocalDateTime.class), decision, rs.getString("return_method_code"),
                rs.getObject("method_chosen_at", LocalDateTime.class),
                rs.getObject("inspection_started_at", LocalDateTime.class),
                rs.getObject("inspection_due_at", LocalDateTime.class), problem, rs.getInt("refund_attempts"),
                rs.getObject("next_action_at", LocalDateTime.class), null,
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)));
    }

    private static ReturnStatus status(String value) {
        return value == null ? null : ReturnStatus.valueOf(value);
    }
}
