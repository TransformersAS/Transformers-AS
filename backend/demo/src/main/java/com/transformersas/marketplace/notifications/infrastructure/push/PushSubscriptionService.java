package com.transformersas.marketplace.notifications.infrastructure.push;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class PushSubscriptionService {
    public record Keys(String p256dh,String auth) { @Override public String toString(){return "Keys[redacted]";} }
    public record Input(String endpoint,Keys keys) { @Override public String toString(){return "PushSubscription[redacted]";} }
    public record Subscription(String id,String endpoint,String p256dh,String auth) {
        @Override public String toString(){return "PushSubscription[id="+id+"]";}
    }
    private final JdbcTemplate jdbc;
    private final PushSettings settings;
    public PushSubscriptionService(JdbcTemplate jdbc,PushSettings settings) {this.jdbc=jdbc;this.settings=settings;}
    public static URI validateEndpoint(String endpoint) {
        try {
            if(endpoint==null || endpoint.length()>2048) throw new IllegalArgumentException();
            URI uri=URI.create(endpoint);String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT);
            boolean trusted=host.equals("fcm.googleapis.com") || host.equals("updates.push.services.mozilla.com")
                    || host.equals("web.push.apple.com") || host.endsWith(".notify.windows.com");
            if(!trusted || !"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getFragment()!=null
                    || (uri.getPort()!=-1 && uri.getPort()!=443)) throw new IllegalArgumentException();
            return uri;
        } catch(RuntimeException invalid) {throw new ResponseStatusException(BAD_REQUEST,"Endpoint push no admitido");}
    }
    static String hash(String endpoint) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void validateKeys(Keys keys) {
        try {
            if(keys==null || keys.p256dh()==null || keys.auth()==null || keys.p256dh().length()>150 || keys.auth().length()>100)
                throw new IllegalArgumentException();
            byte[] point=Base64.getUrlDecoder().decode(keys.p256dh());
            if(point.length!=65 || point[0]!=4 || Base64.getUrlDecoder().decode(keys.auth()).length!=16)
                throw new IllegalArgumentException();
            if(!org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("prime256v1").getCurve().decodePoint(point).isValid())
                throw new IllegalArgumentException();
        } catch(RuntimeException invalid) {throw new ResponseStatusException(BAD_REQUEST,"Claves de suscripción inválidas");}
    }
    @Transactional
    public String save(Long account,String recipientType,Long recipient,String sessionId,Input input) {
        if(!settings.ready()) throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Push aún no está configurado");
        if(input==null) throw new ResponseStatusException(BAD_REQUEST);
        validateEndpoint(input.endpoint());validateKeys(input.keys());
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
        String session=jdbc.queryForObject("SELECT PRIMARY_ID FROM SPRING_SESSION WHERE SESSION_ID=?",String.class,sessionId);
        var rows=jdbc.queryForList("SELECT account_id FROM push_subscriptions WHERE endpoint_hash=?",Long.class,hash(input.endpoint()));
        if(!rows.isEmpty() && !rows.getFirst().equals(account)) throw new ResponseStatusException(CONFLICT,"Desactiva la suscripción anterior de este navegador");
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions WHERE account_id=?",Integer.class,account);
        if(rows.isEmpty() && count>=5) throw new ResponseStatusException(CONFLICT,"Límite de cinco navegadores por cuenta");
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO push_subscriptions(id,endpoint_hash,endpoint,p256dh,auth,account_id,recipient_type,recipient_id,session_primary_id)
            VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE p256dh=VALUES(p256dh),auth=VALUES(auth),
              recipient_type=VALUES(recipient_type),recipient_id=VALUES(recipient_id),session_primary_id=VALUES(session_primary_id)
            """,id,hash(input.endpoint()),input.endpoint(),input.keys().p256dh(),input.keys().auth(),account,recipientType,recipient,session);
        // A competing account may insert after the first ownership read. Lock/read the current owner;
        // on conflict the transaction rolls back the upsert, including any attempted reassignment.
        Long owner=jdbc.queryForObject("SELECT account_id FROM push_subscriptions WHERE endpoint_hash=? FOR UPDATE",Long.class,hash(input.endpoint()));
        if(!account.equals(owner)) throw new ResponseStatusException(CONFLICT,"La suscripción pertenece a otra cuenta");
        return jdbc.queryForObject("SELECT id FROM push_subscriptions WHERE endpoint_hash=?",String.class,hash(input.endpoint()));
    }
    public void remove(Long account,String endpoint) {
        if(endpoint==null || endpoint.length()>2048) throw new ResponseStatusException(BAD_REQUEST);
        jdbc.update("DELETE FROM push_subscriptions WHERE account_id=? AND endpoint_hash=?",account,hash(endpoint));
    }
    public List<Subscription> recipients(String type,Long recipient) {
        return jdbc.query("""
            SELECT s.* FROM push_subscriptions s JOIN SPRING_SESSION ss ON ss.PRIMARY_ID=s.session_primary_id
            JOIN user_accounts a ON a.id=s.account_id
            WHERE s.recipient_type=? AND s.recipient_id=? AND a.status='ACTIVA' AND ss.PRINCIPAL_NAME=a.email
              AND ss.EXPIRY_TIME > ?
              AND EXISTS(SELECT 1 FROM user_account_roles r WHERE r.account_id=s.account_id AND r.role=?)
              AND (?='BUYER' OR EXISTS(SELECT 1 FROM stores st WHERE st.id=s.recipient_id AND st.owner_account_id=s.account_id))
            LIMIT 5
            """,(r,n)->new Subscription(r.getString("id"),r.getString("endpoint"),r.getString("p256dh"),r.getString("auth")),
            type,recipient,System.currentTimeMillis(),type.equals("BUYER")?"COMPRADOR":"VENDEDOR",type);
    }
    public void expire(String id) {jdbc.update("DELETE FROM push_subscriptions WHERE id=?",id);}
    public boolean registered(Long account,String sessionId,String type,Long recipient,String endpoint) {
        if(endpoint==null || endpoint.length()>2048) return false;
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM push_subscriptions p JOIN SPRING_SESSION s ON s.PRIMARY_ID=p.session_primary_id
            WHERE p.account_id=? AND s.SESSION_ID=? AND p.recipient_type=? AND p.recipient_id=? AND p.endpoint_hash=?
            """,Integer.class,account,sessionId,type,recipient,hash(endpoint))>0;
    }
}
