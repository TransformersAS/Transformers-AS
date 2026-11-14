package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.application.dto.BuyerRefund;
import com.transformersas.marketplace.orders.application.port.BuyerRefundQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class JdbcBuyerRefundQuery implements BuyerRefundQuery {
    private final JdbcTemplate jdbc;
    public JdbcBuyerRefundQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<BuyerRefund> findByOrderId(Long orderId) {
        return jdbc.query("""
            SELECT e.reason,e.amount,p.currency,e.status,e.externally_confirmed,
                NULL AS provider_reference,e.created_at,e.updated_at,e.confirmed_at,0 AS local_record,
                CONCAT('external-',e.payment_id) AS sort_key
            FROM external_payment_refunds e
            JOIN external_payment_observations p ON p.payment_id=e.payment_id
            JOIN payment_checkouts c ON c.id=p.checkout_id
            WHERE c.order_id=? OR p.order_id=?
            UNION ALL
            SELECT CASE WHEN r.idempotency_key=CONCAT('order-cancel-',r.order_id)
                        THEN 'CANCELLED_ORDER' ELSE 'OTHER' END,
                r.amount,NULL,r.status,FALSE,r.provider_reference,r.created_at,r.updated_at,NULL,1,
                CONCAT('local-',r.id)
            FROM refunds r JOIN orders o ON o.id=r.order_id
            WHERE r.order_id=? AND NOT EXISTS (
                SELECT 1 FROM external_payment_refunds e
                JOIN external_payment_observations p ON p.payment_id=e.payment_id
                JOIN payment_checkouts c ON c.id=p.checkout_id
                WHERE (c.order_id=r.order_id OR p.order_id=r.order_id)
                  AND (e.legacy_refund_id=r.id OR (e.reason='CANCELLED_ORDER'
                    AND o.transaction_id=CONCAT('MP-',e.payment_id)
                    AND r.idempotency_key=CONCAT('order-cancel-',r.order_id))))
            ORDER BY created_at,sort_key
            """, (rs, index) -> map(rs), orderId, orderId, orderId);
    }

    private BuyerRefund map(ResultSet rs) throws SQLException {
        boolean local = rs.getBoolean("local_record");
        String state = rs.getString("status");
        String reference = rs.getString("provider_reference");
        if (local && "COMPLETED".equals(state)) {
            state = reference != null && reference.startsWith("SIM-") ? "SIMULATED" : "COMPLETED_RECORDED";
        } else if (!local && rs.getBoolean("externally_confirmed")) {
            state = "EXCEPTION".equals(state) ? "CONFIRMED_REVIEW" : "CONFIRMED";
        } else if (!local && "CONFIRMED".equals(state)) {
            state = "EXCEPTION";
        }
        String label = switch (state) {
            case "INTENT" -> "Solicitud registrada";
            case "SUBMITTING", "UNKNOWN" -> "Verificando devolución";
            case "PENDING" -> "Devolución en proceso";
            case "CONFIRMED" -> "Devolución confirmada";
            case "FAILED" -> "No se pudo completar";
            case "CONFIRMED_REVIEW" -> "Devolución confirmada; registro en revisión";
            case "SIMULATED" -> "Resultado simulado";
            case "COMPLETED_RECORDED" -> "Finalización registrada sin confirmación externa disponible";
            default -> "Requiere revisión";
        };
        String message = switch (state) {
            case "INTENT" -> "La solicitud está registrada; aún no se ha enviado al proveedor.";
            case "SUBMITTING", "UNKNOWN" -> "Se está verificando el resultado. Todavía no hay confirmación de devolución.";
            case "PENDING" -> "La devolución está en proceso y aún no está confirmada.";
            case "CONFIRMED" -> "El proveedor confirmó la devolución del importe indicado.";
            case "FAILED" -> "La devolución no se completó. Su resultado quedó registrado para seguimiento.";
            case "CONFIRMED_REVIEW" -> "El proveedor confirmó la devolución; la actualización del registro local requiere revisión.";
            case "SIMULATED" -> "Este resultado pertenece al simulador y no acredita una devolución de dinero real.";
            case "COMPLETED_RECORDED" -> "Existe un registro local de finalización; no hay evidencia externa disponible en este seguimiento.";
            default -> "No hay una resolución verificable completa. La devolución requiere revisión.";
        };
        String reason = switch (rs.getString("reason")) {
            case "CANCELLED_ORDER" -> "Cancelación del pedido";
            case "ADDITIONAL_PAYMENT" -> "Cobro adicional";
            default -> "Devolución asociada al pedido";
        };
        return new BuyerRefund(reason, rs.getBigDecimal("amount"), rs.getString("currency"), state,
            label, message, rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            !local && rs.getBoolean("externally_confirmed") ? rs.getObject("confirmed_at", LocalDateTime.class) : null);
    }
}
