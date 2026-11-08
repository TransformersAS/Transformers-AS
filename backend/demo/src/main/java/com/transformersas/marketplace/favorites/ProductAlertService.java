package com.transformersas.marketplace.favorites;

import com.transformersas.marketplace.product.*;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class ProductAlertService {
    public record Input(Long productId, String kind, String variantName, BigDecimal targetPrice) { }
    public record Alert(String id, Long productId, String productName, String kind, String variantName,
                        BigDecimal targetPrice, String status, int revision) { }
    public record Result(boolean alreadySatisfied, Alert alert) { }
    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final PublishNotificationUseCase notifications;
    private final FollowedProductVisibility visibility;
    public ProductAlertService(JdbcTemplate jdbc, ProductRepository products, PublishNotificationUseCase notifications,FollowedProductVisibility visibility) {
        this.jdbc=jdbc; this.products=products; this.notifications=notifications;
        this.visibility=visibility;
    }
    @Transactional(readOnly=true)
    public List<Alert> list(Long account) {
        var hidden=visibility.hiddenIds();
        return jdbc.query("""
            SELECT a.*,CASE WHEN p.status IN ('DRAFT','RETIRED') THEN 'Publicación no disponible' ELSE p.name END product_name FROM product_alerts a JOIN products p ON p.id=a.product_id
            WHERE a.account_id=? AND a.status<>'DELETED' ORDER BY a.created_at DESC,a.id LIMIT 500
            """,(r,n)->new Alert(r.getString("id"),r.getLong("product_id"),hidden.contains(r.getString("product_id"))?"Publicación no disponible":r.getString("product_name"),r.getString("kind"),
                r.getString("variant_name"),r.getBigDecimal("target_price"),r.getString("status"),r.getInt("revision")),account);
    }
    private Product validate(Input input) {
        if (input==null || input.productId()==null || !Set.of("PRICE","AVAILABILITY").contains(Objects.toString(input.kind(),"")))
            throw new ResponseStatusException(BAD_REQUEST,"Tipo de alerta inválido");
        Product p=products.findById(input.productId()).orElseThrow(()->new ResponseStatusException(NOT_FOUND));
        if (!visibility.readable(p)) throw new ResponseStatusException(NOT_FOUND,"Producto no disponible");
        if (!Boolean.TRUE.equals(p.getActive()) || p.getStatus()!=ProductStatus.ACTIVE)
            throw new ResponseStatusException(CONFLICT,"La publicación no está activa");
        if (input.kind().equals("PRICE")) {
            if (input.variantName()!=null || input.targetPrice()==null || input.targetPrice().signum()<=0
                    || input.targetPrice().scale()>2 || input.targetPrice().precision()>19 || input.targetPrice().precision()-input.targetPrice().scale()>17)
                throw new ResponseStatusException(BAD_REQUEST,"Define un precio objetivo positivo con máximo dos decimales");
        } else {
            if (input.targetPrice()!=null || input.variantName()==null || input.variantName().isBlank() || input.variantName().length()>255)
                throw new ResponseStatusException(BAD_REQUEST,"Selecciona una variante agotada");
            var variant=p.getVariants().stream().filter(Objects::nonNull).filter(v->Objects.equals(v.getName(),input.variantName())).findFirst()
                    .orElseThrow(()->new ResponseStatusException(BAD_REQUEST,"La variante no existe"));
            if (variant.getStock()==null || variant.getStock()>0) throw new ResponseStatusException(CONFLICT,"La variante ya está disponible o no tiene inventario válido");
        }
        return p;
    }
    @Transactional
    public Result create(Long account, Input input) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
        Product p=validate(input);
        if (input.kind().equals("PRICE") && p.getPrice().compareTo(input.targetPrice())<=0) return new Result(true,null);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM product_alerts WHERE account_id=? AND status<>'DELETED'",Integer.class,account);
        if (count>=500) throw new ResponseStatusException(CONFLICT,"Límite de 500 alertas; elimina alguna para continuar");
        Integer duplicates=jdbc.queryForObject("""
                SELECT COUNT(*) FROM product_alerts WHERE account_id=? AND product_id=? AND kind=?
                AND variant_name <=> ? AND status IN ('ACTIVE','PAUSED')
                """,Integer.class,account,input.productId(),input.kind(),input.variantName());
        if (duplicates>0) throw new ResponseStatusException(CONFLICT,"Ya tienes una alerta para esta publicación o variante; modifica la existente");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO product_alerts(id,account_id,product_id,kind,variant_name,target_price,status) VALUES (?,?,?,?,?,?,'ACTIVE')",
                id,account,input.productId(),input.kind(),input.variantName(),input.targetPrice());
        return new Result(false,own(account,id));
    }
    private Alert own(Long account,String id) {
        return jdbc.query("""
            SELECT a.*,p.name product_name FROM product_alerts a JOIN products p ON p.id=a.product_id
            WHERE a.id=? AND a.account_id=? AND a.status<>'DELETED' FOR UPDATE
            """,(r,n)->new Alert(r.getString("id"),r.getLong("product_id"),r.getString("product_name"),r.getString("kind"),
            r.getString("variant_name"),r.getBigDecimal("target_price"),r.getString("status"),r.getInt("revision")),id,account)
            .stream().findFirst().orElseThrow(()->new ResponseStatusException(NOT_FOUND));
    }
    @Transactional
    public Result edit(Long account,String id,Input input) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
        Alert before=own(account,id);
        if (input==null) throw new ResponseStatusException(BAD_REQUEST);
        if (!Objects.equals(before.productId(),input.productId()) || !Objects.equals(before.kind(),input.kind()))
            throw new ResponseStatusException(BAD_REQUEST,"No se puede cambiar el producto ni el tipo de alerta");
        Product p=validate(input);
        Integer duplicates=jdbc.queryForObject("SELECT COUNT(*) FROM product_alerts WHERE account_id=? AND product_id=? AND kind=? AND variant_name <=> ? AND id<>? AND status IN ('ACTIVE','PAUSED')",
                Integer.class,account,input.productId(),input.kind(),input.variantName(),id);
        if (duplicates>0) throw new ResponseStatusException(CONFLICT,"Ya existe otra alerta para esa condición");
        boolean satisfied=input.kind().equals("PRICE") && p.getPrice().compareTo(input.targetPrice())<=0;
        jdbc.update("UPDATE product_alerts SET variant_name=?,target_price=?,status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                input.variantName(),input.targetPrice(),satisfied?"TRIGGERED":before.status().equals("PAUSED")?"PAUSED":"ACTIVE",id);
        return new Result(satisfied,own(account,id));
    }
    @Transactional
    public void changeStatus(Long account,String id,String status) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
        Alert a=own(account,id);
        if (!Set.of("PAUSED","ACTIVE","DELETED").contains(Objects.toString(status,""))) throw new ResponseStatusException(BAD_REQUEST);
        if (status.equals("ACTIVE")) {
            Integer duplicates=jdbc.queryForObject("SELECT COUNT(*) FROM product_alerts WHERE account_id=? AND product_id=? AND kind=? AND variant_name <=> ? AND id<>? AND status IN ('ACTIVE','PAUSED')",
                    Integer.class,account,a.productId(),a.kind(),a.variantName(),id);
            if (duplicates>0) throw new ResponseStatusException(CONFLICT,"Ya existe otra alerta para esa condición");
            Product p=validate(new Input(a.productId(),a.kind(),a.variantName(),a.targetPrice()));
            if (a.kind().equals("PRICE") && p.getPrice().compareTo(a.targetPrice())<=0)
                throw new ResponseStatusException(CONFLICT,"El precio objetivo ya se cumple; abre el producto");
        }
        jdbc.update("UPDATE product_alerts SET status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",status,id);
    }
    @Transactional
    public void evaluate(String id) {
        var owners=jdbc.queryForList("SELECT account_id FROM product_alerts WHERE id=?",Long.class,id);
        if (owners.isEmpty()) return;
        Alert a;
        try { a=own(owners.getFirst(),id); } catch (ResponseStatusException absent) { return; }
        if (!a.status().equals("ACTIVE")) return;
        Product p=products.findById(a.productId()).orElse(null);
        if (p==null || !Boolean.TRUE.equals(p.getActive()) || p.getStatus()!=ProductStatus.ACTIVE || !visibility.readable(p)) return;
        boolean satisfied=a.kind().equals("PRICE") ? p.getPrice().compareTo(a.targetPrice())<=0 :
                p.getVariants().stream().filter(Objects::nonNull).anyMatch(v->Objects.equals(v.getName(),a.variantName()) && v.getStock()!=null && v.getStock()>0);
        if (!satisfied) return;
        notifications.execute(new PublishNotificationCommand(RecipientType.BUYER,owners.getFirst(),"PRODUCT_ALERT",
                "PRICE".equals(a.kind())?"Se cumplió tu alerta de precio":"Tu variante vuelve a estar disponible",
                p.getName()+(a.variantName()==null?"":" · "+a.variantName()),"PRODUCT",p.getId().toString(),"product-alert-"+id+"-"+a.revision()));
        jdbc.update("UPDATE product_alerts SET status='TRIGGERED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
    }
}
