package com.transformersas.marketplace.users.infrastructure.persistence.repository;

import com.transformersas.marketplace.users.domain.model.PersonalProfile;
import com.transformersas.marketplace.users.domain.repository.PersonalProfileRepository;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Repository
public class JdbcPersonalProfileRepository implements PersonalProfileRepository {
    private final JdbcTemplate jdbc;
    public JdbcPersonalProfileRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Identity identity(Long id,boolean lock) {
        return jdbc.query("SELECT id,email,password_hash,status FROM user_accounts WHERE id=?"+(lock?" FOR UPDATE":""),
            (r,n)->new Identity(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)),id).stream().findFirst()
            .orElseThrow(()->BusinessException.unauthenticated("ACCOUNT_NOT_FOUND","La cuenta no existe"));
    }
    @Override public PersonalProfile profile(Long id) {
        return jdbc.queryForObject("""
            SELECT a.email,p.full_name,p.birth_date,p.phone,COALESCE(s.promotions,FALSE) promotions,
                COALESCE(s.marketplace_news,FALSE) news,COALESCE(s.version,0) version,
                e.new_email,e.expires_at
            FROM user_accounts a LEFT JOIN account_profiles p ON p.account_id=a.id
            LEFT JOIN account_profile_settings s ON s.account_id=a.id
            LEFT JOIN account_email_changes e ON e.account_id=a.id AND e.used_at IS NULL WHERE a.id=?
            """,(r,n)->new PersonalProfile(r.getString("full_name"),r.getObject("birth_date",LocalDate.class),r.getString("email"),
                r.getString("phone"),r.getBoolean("promotions"),r.getBoolean("news"),true,r.getLong("version"),
                r.getString("new_email"),r.getObject("expires_at",LocalDateTime.class)),id);
    }
    @Override public void update(Long id,String name,LocalDate birth,String phone,boolean promotions,boolean news) {
        jdbc.update("""
            INSERT INTO account_profiles(account_id,full_name,birth_date,phone) VALUES (?,?,?,?)
            ON DUPLICATE KEY UPDATE full_name=VALUES(full_name),birth_date=VALUES(birth_date),phone=VALUES(phone)
            """,id,name.strip(),birth,phone.strip());
        jdbc.update("""
            INSERT INTO account_profile_settings(account_id,promotions,marketplace_news,version) VALUES (?,?,?,1)
            ON DUPLICATE KEY UPDATE promotions=VALUES(promotions),marketplace_news=VALUES(marketplace_news),version=version+1
            """,id,promotions,news);
    }
    private void revision(Long id) {
        jdbc.update("INSERT INTO account_profile_settings(account_id,version) VALUES (?,1) ON DUPLICATE KEY UPDATE version=version+1",id);
    }
    @Override public boolean emailOccupied(Long id,String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE email=? AND id<>?",Integer.class,email,id)>0;
    }
    @Override public void pendingEmail(Long id,String old,String next,String hash,String fingerprint) {
        jdbc.update("""
            INSERT INTO account_email_changes(account_id,previous_email,new_email,token_hash,credential_fingerprint,expires_at,used_at)
            VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(6)+INTERVAL 30 MINUTE,NULL)
            ON DUPLICATE KEY UPDATE previous_email=VALUES(previous_email),new_email=VALUES(new_email),token_hash=VALUES(token_hash),
                credential_fingerprint=VALUES(credential_fingerprint),expires_at=VALUES(expires_at),used_at=NULL
            """,id,old,next,hash,fingerprint);
    }
    @Override public PendingEmail pendingEmail(Long id) {
        return jdbc.query("SELECT previous_email,new_email,token_hash,credential_fingerprint FROM account_email_changes WHERE account_id=? AND used_at IS NULL",
            (r,n)->new PendingEmail(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),id).stream().findFirst().orElse(null);
    }
    @Override public boolean consumeEmail(Long id,String hash) {
        return jdbc.update("UPDATE account_email_changes SET used_at=CURRENT_TIMESTAMP(6) WHERE account_id=? AND token_hash=? AND used_at IS NULL AND expires_at>CURRENT_TIMESTAMP(6)",id,hash)==1;
    }
    @Override public void confirmEmail(Long id,String email,String old) {
        jdbc.update("UPDATE user_accounts SET email=?,email_verified_at=CURRENT_TIMESTAMP(6) WHERE id=?",email,id);
        revision(id);
        // Un token emitido al correo anterior no puede recuperar la identidad modificada.
        jdbc.update("UPDATE password_recovery_tokens SET used_at=CURRENT_TIMESTAMP(6) WHERE account_id=? AND used_at IS NULL",id);
        jdbc.update("UPDATE email_verification_tokens SET used_at=CURRENT_TIMESTAMP(6) WHERE account_id=? AND used_at IS NULL",id);
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME IN (?,?)",old,email);
    }
    private static final String OWN_STORE="SELECT id FROM stores WHERE owner_account_id=?";
    private void count(List<Blocker> out,String code,String message,String sql,Object... args) {
        Long count=jdbc.queryForObject(sql,Long.class,args);if(count!=null && count>0)out.add(new Blocker(code,message,count));
    }
    @Override public List<Blocker> blockers(Long id) {
        var out=new ArrayList<Blocker>();
        String own="(o.account_id=? OR o.store_id IN ("+OWN_STORE+"))";
        count(out,"ORDERS","Pedidos sin finalizar","SELECT COUNT(*) FROM orders o WHERE "+own+" AND o.status NOT IN ('DELIVERED','CANCELLED','RETURNED_TO_SELLER')",id,id);
        count(out,"PAYMENTS","Pagos o reembolsos de pedidos pendientes","SELECT COUNT(*) FROM orders o WHERE "+own+" AND o.payment_status='REFUND_PENDING'",id,id);
        count(out,"REFUNDS","Devoluciones de dinero sin completar","SELECT COUNT(*) FROM refunds r JOIN orders o ON o.id=r.order_id WHERE "+own+" AND r.status<>'COMPLETED'",id,id);
        count(out,"CLAIMS","Reclamaciones o sus reembolsos pendientes","SELECT COUNT(*) FROM claims c WHERE (c.buyer_account_id=? OR c.store_id IN ("+OWN_STORE+")) AND (c.status<>'RESOLVED' OR c.refund_status IN ('PENDING','FAILED'))",id,id);
        count(out,"RETURNS","Devoluciones de productos sin finalizar","SELECT COUNT(*) FROM return_requests r WHERE (r.buyer_account_id=? OR r.store_id IN ("+OWN_STORE+")) AND r.status NOT IN ('REJECTED','FINISHED')",id,id);
        String checkout="(c.account_id=? OR CAST(JSON_UNQUOTE(JSON_EXTRACT(c.snapshot_json,'$.storeId')) AS UNSIGNED) IN ("+OWN_STORE+"))";
        count(out,"CHECKOUTS","Compras en curso o en revisión","SELECT COUNT(*) FROM payment_checkouts c WHERE "+checkout+" AND (c.status='REVIEW' OR (c.cancelled_at IS NULL AND c.status IN ('STARTING','PENDING','REJECTED') AND c.expires_at>CURRENT_TIMESTAMP(6)))",id,id);
        count(out,"EXTERNAL_PAYMENTS","Pagos externos que requieren resolución","SELECT COUNT(*) FROM external_payment_observations p JOIN payment_checkouts c ON c.id=p.checkout_id WHERE "+checkout+" AND p.resolution_status IN ('PENDING_LOCAL','PAYMENT_MISMATCH','REQUIRES_REVIEW','ADDITIONAL_PAYMENT','FINANCIAL_UPDATE_PENDING','FINANCIAL_EXCEPTION')",id,id);
        count(out,"EXTERNAL_REFUNDS","Devoluciones externas pendientes o en revisión","SELECT COUNT(*) FROM external_payment_refunds r JOIN external_payment_observations p ON p.payment_id=r.payment_id JOIN payment_checkouts c ON c.id=p.checkout_id WHERE "+checkout+" AND (r.externally_confirmed=FALSE OR r.status='EXCEPTION')",id,id);
        count(out,"RESERVATIONS","Reservas de inventario vigentes","SELECT COUNT(*) FROM inventory_reservations r JOIN products p ON p.id=r.product_id WHERE (r.account_id=? OR p.store_id IN ("+OWN_STORE+")) AND r.status='ACTIVE' AND r.expires_at>CURRENT_TIMESTAMP(6)",id,id);
        count(out,"STORE_INFORMATION","Solicitudes de aclaración de la tienda pendientes","SELECT COUNT(*) FROM store_clarification_requests WHERE store_id IN ("+OWN_STORE+") AND status='OPEN'",id);
        return List.copyOf(out);
    }
    @Override public void deactivate(Long id,String email) {
        // Se retiran las ofertas de la cuenta cerrada; nunca se borran tienda, productos ni historia.
        jdbc.update("UPDATE products SET active=FALSE,status=CASE WHEN status='RETIRED' THEN 'RETIRED' ELSE 'PAUSED' END WHERE store_id IN ("+OWN_STORE+")",id);
        jdbc.update("UPDATE stores SET status='SUSPENDED',status_reason='Cuenta desactivada por solicitud de su titular',version=version+1 WHERE owner_account_id=?",id);
        jdbc.update("UPDATE user_accounts SET status='INACTIVA' WHERE id=?",id);
        jdbc.update("DELETE FROM account_email_changes WHERE account_id=?",id);
        jdbc.update("UPDATE password_recovery_tokens SET used_at=CURRENT_TIMESTAMP(6) WHERE account_id=? AND used_at IS NULL",id);
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?",email);
        revision(id);
    }
}
