package com.transformersas.marketplace.notifications.infrastructure.persistence.repository;

import com.transformersas.marketplace.notifications.domain.model.ExternalStatus;
import com.transformersas.marketplace.notifications.domain.model.Notification;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.notifications.domain.repository.NotificationRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/** Adaptador JDBC. La deduplicación la garantiza el UNIQUE(event_key), no una lectura previa. */
@Repository
public class JdbcNotificationRepository implements NotificationRepository {

    private static final int MAX_ERROR = 500;

    private static final String COLUMNS = """
            id, recipient_type, recipient_id, type, title, message, reference_type, reference_id, event_key,
            external_status, attempts, last_error, correlation_id, created_at""";

    private final JdbcClient jdbc;

    public JdbcNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Insertion insertIfAbsent(Notification notification) {
        try {
            jdbc.sql("""
                            INSERT INTO notifications (recipient_type, recipient_id, type, title, message, reference_type,
                                                       reference_id, event_key, external_status, attempts, last_error,
                                                       correlation_id, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(notification.recipientType().name(), notification.recipientId(), notification.type(),
                            notification.title(), notification.message(), notification.referenceType(),
                            notification.referenceId(), notification.eventKey(), notification.externalStatus().name(),
                            notification.attempts(), notification.lastError(), notification.correlationId(),
                            notification.createdAt(), notification.createdAt())
                    .update();
            return new Insertion(findByEventKey(notification.eventKey()).orElseThrow(), true);
        } catch (DuplicateKeyException duplicate) {
            // Mismo evento ya registrado (otra petición o repetición): se devuelve la existente.
            return new Insertion(findByEventKey(notification.eventKey()).orElseThrow(() -> duplicate), false);
        }
    }

    @Override
    public Optional<Notification> findById(Long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM notifications WHERE id = ?").param(id)
                .query((rs, row) -> map(rs)).optional();
    }

    private Optional<Notification> findByEventKey(String eventKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM notifications WHERE event_key = ?").param(eventKey)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public void markSent(Long id) {
        jdbc.sql("""
                        UPDATE notifications SET external_status = 'SENT', attempts = attempts + 1, last_error = NULL,
                               updated_at = ? WHERE id = ?""")
                .params(LocalDateTime.now(), id).update();
    }

    @Override
    public void markFailed(Long id, String error) {
        String truncated = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR));
        jdbc.sql("""
                        UPDATE notifications SET external_status = 'FAILED', attempts = attempts + 1, last_error = ?,
                               updated_at = ? WHERE id = ?""")
                .params(truncated, LocalDateTime.now(), id).update();
    }

    private static Notification map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Notification(rs.getLong("id"), RecipientType.valueOf(rs.getString("recipient_type")),
                rs.getObject("recipient_id", Long.class), rs.getString("type"), rs.getString("title"),
                rs.getString("message"), rs.getString("reference_type"), rs.getString("reference_id"),
                rs.getString("event_key"), ExternalStatus.valueOf(rs.getString("external_status")),
                rs.getInt("attempts"), rs.getString("last_error"), rs.getString("correlation_id"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
