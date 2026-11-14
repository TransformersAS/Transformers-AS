package com.transformersas.marketplace.notifications.application.usecase;

import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.orders.domain.model.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Publica dentro de la transacción del evento de negocio; no llama al proveedor. */
@Service
public class OrderNotificationPublisher {
    private final PublishNotificationUseCase publish;
    private final JdbcTemplate jdbc;
    public OrderNotificationPublisher(PublishNotificationUseCase publish,JdbcTemplate jdbc) {this.publish=publish;this.jdbc=jdbc;}
    public void purchased(Order order) {
        send(RecipientType.BUYER,order.accountId(),"PURCHASED","Compra confirmada","Tu pedido #"+order.id()+" fue confirmado.","ORDER",order.id().toString(),"order-"+order.id()+"-PURCHASED");
        send(RecipientType.STORE,order.storeId(),"PURCHASED","Nueva compra confirmada","Se confirmó el pedido #"+order.id()+" de tu tienda.","ORDER",order.id().toString(),"order-"+order.id()+"-PURCHASED-STORE");
    }
    public void financial(String payment,String outcome) {
        var row=jdbc.queryForMap("""
            SELECT c.account_id,c.order_id,c.id AS checkout_id,o.store_id
            FROM external_payment_observations p JOIN payment_checkouts c ON c.id=p.checkout_id
            LEFT JOIN orders o ON o.id=c.order_id WHERE p.payment_id=?
            """,payment);
        boolean confirmed=outcome.startsWith("CONFIRMED");
        String title=confirmed?"Devolución confirmada":"Tu devolución requiere revisión";
        String message=confirmed ? (outcome.equals("CONFIRMED_REVIEW")
            ?"El proveedor confirmó la devolución; el registro local requiere revisión."
            :"El proveedor confirmó la devolución. Puedes consultar su seguimiento.")
            :"No se pudo verificar una resolución completa de la devolución. Quedó registrada para revisión.";
        String reference=row.get("order_id")==null?"CHECKOUT":"ORDER";
        String id=row.get("order_id")==null?(String)row.get("checkout_id"):row.get("order_id").toString();
        String key="mp-refund-"+payment+"-"+outcome;
        send(RecipientType.BUYER,((Number)row.get("account_id")).longValue(),"REFUND_"+outcome,title,message,reference,id,key);
        if(row.get("store_id")!=null) send(RecipientType.STORE,((Number)row.get("store_id")).longValue(),"REFUND_"+outcome,title,message,reference,id,key+"-STORE");
    }
    private void send(RecipientType type,Long recipient,String event,String title,String message,String reference,String id,String key) {
        if(recipient!=null) publish.execute(new PublishNotificationCommand(type,recipient,event,title,message,reference,id,key));
    }
}
