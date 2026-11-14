package com.transformersas.marketplace.messaging.infrastructure;

import com.transformersas.marketplace.messaging.domain.ConversationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class JdbcConversationRepository implements ConversationRepository {
    private final JdbcTemplate jdbc;
    public JdbcConversationRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Optional<Context> context(long buyer,long product,Long order) {
        String condition=order==null?" AND p.active=TRUE AND p.status='ACTIVE' AND s.status='ACTIVE'":
            " AND EXISTS(SELECT 1 FROM orders o JOIN order_items i ON i.order_id=o.id WHERE o.id=? AND o.account_id=? AND i.product_id=p.id)";
        Object[] args=order==null?new Object[]{product}:new Object[]{product,order,buyer};
        return jdbc.query("SELECT s.owner_account_id,s.id,p.name,s.name FROM products p JOIN stores s ON s.id=p.store_id WHERE p.id=? AND s.owner_account_id IS NOT NULL"+condition,
            (r,n)->new Context(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4)),args).stream().findFirst();
    }
    public long start(long buyer,long product,Long order,Context c) {
        // Unique generated context handles NULL and simultaneous requests; no catch in a rollback-only transaction.
        jdbc.update("INSERT INTO conversations(buyer_id,seller_id,store_id,product_id,order_id) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)",buyer,c.sellerId(),c.storeId(),product,order);
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO conversation_participants(conversation_id,account_id) VALUES(?,?),(?,?) ON DUPLICATE KEY UPDATE account_id=VALUES(account_id)",id,buyer,id,c.sellerId());
        return id;
    }
    private static final String THREADS="""
        SELECT c.*,p.name product_name,s.name store_name,a.archived,a.read_through,
        (SELECT COUNT(*) FROM conversation_messages m WHERE m.conversation_id=c.id AND m.sender_id<>a.account_id AND m.id>a.read_through) unread
        FROM conversations c JOIN conversation_participants a ON a.conversation_id=c.id
        JOIN products p ON p.id=c.product_id JOIN stores s ON s.id=c.store_id
        WHERE a.account_id=? AND (c.buyer_id=? OR (c.seller_id=? AND s.owner_account_id=?))
        """;
    private List<Thread> threads(long account,String where,Object...values) {
        var args=new ArrayList<Object>(List.of(account,account,account,account));args.addAll(Arrays.asList(values));
        return jdbc.query(THREADS+where,(r,n)->new Thread(r.getLong("id"),r.getLong("buyer_id"),r.getLong("seller_id"),r.getLong("store_id"),r.getLong("product_id"),r.getObject("order_id",Long.class),r.getString("product_name"),r.getString("store_name"),r.getBoolean("closed"),r.getBoolean("archived"),r.getLong("unread"),r.getLong("read_through"),r.getTimestamp("updated_at").toLocalDateTime()),args.toArray());
    }
    public Optional<Thread> find(long account,long id,boolean lock) {return threads(account," AND c.id=?"+(lock?" FOR UPDATE":""),id).stream().findFirst();}
    public List<Thread> list(long account,boolean archived,int page) {return threads(account," AND a.archived=? ORDER BY c.updated_at DESC,c.id DESC LIMIT 30 OFFSET ?",archived,page*30);}
    private static final String MESSAGES="SELECT m.*,EXISTS(SELECT 1 FROM conversation_images i WHERE i.message_id=m.id) image FROM conversation_messages m ";
    private List<Message> query(String condition,Object...args) {return jdbc.query(MESSAGES+condition,(r,n)->new Message(r.getLong("id"),r.getLong("conversation_id"),r.getLong("sender_id"),r.getString("text"),r.getTimestamp("created_at").toLocalDateTime(),r.getBoolean("image")),args);}
    public List<Message> messages(long thread,long after,long before,int size) {
        var result=new ArrayList<>(query("WHERE m.conversation_id=? AND m.id>? AND m.id<? ORDER BY m.id "+(after>0?"ASC":"DESC")+" LIMIT ?",thread,after,before,size));
        if(after==0) Collections.reverse(result);return result;
    }
    public Optional<Message> duplicate(long thread,long sender,String key) {return query("WHERE m.conversation_id=? AND m.sender_id=? AND m.client_key=? FOR UPDATE",thread,sender,key).stream().findFirst();}
    public Optional<Message> message(long id) {return query("WHERE m.id=?",id).stream().findFirst();}
    public Message append(long thread,long sender,String key,String text,Image image) {
        jdbc.update("INSERT INTO conversation_messages(conversation_id,sender_id,client_key,text) VALUES(?,?,?,?)",thread,sender,key,text);
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        if(image!=null) jdbc.update("INSERT INTO conversation_images(message_id,content_type,sha256,content) VALUES(?,?,?,?)",id,image.type(),image.hash(),image.bytes());
        jdbc.update("UPDATE conversations SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",thread);
        jdbc.update("UPDATE conversation_participants SET archived=FALSE WHERE conversation_id=? AND account_id<>?",thread,sender);
        return message(id).orElseThrow();
    }
    public void read(long thread,long account,long through) {jdbc.update("UPDATE conversation_participants SET read_through=GREATEST(read_through,?) WHERE conversation_id=? AND account_id=?",through,thread,account);}
    public void archive(long thread,long account,boolean archived) {jdbc.update("UPDATE conversation_participants SET archived=? WHERE conversation_id=? AND account_id=?",archived,thread,account);}
    public void close(long thread,boolean closed) {jdbc.update("UPDATE conversations SET closed=? WHERE id=?",closed,thread);}
    public boolean canSend(long account,long store,boolean seller) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE id=? AND status='ACTIVA' AND communications_restricted=FALSE",Integer.class,account)==1 &&
            (!seller || jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE id=? AND owner_account_id=? AND status='ACTIVE'",Integer.class,store,account)==1);
    }
    public Optional<Image> image(long message) {return jdbc.query("SELECT content_type,sha256,content FROM conversation_images WHERE message_id=?",(r,n)->new Image(r.getString(1),r.getString(2),r.getBytes(3)),message).stream().findFirst();}
}
