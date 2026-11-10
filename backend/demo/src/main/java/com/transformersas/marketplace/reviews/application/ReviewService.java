package com.transformersas.marketplace.reviews.application;

import com.transformersas.marketplace.reports.application.ContentVisibility;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class ReviewService {
    public record Input(Long orderId,Long productId,Integer rating,String comment,List<Long> keepImageIds) { }
    public record Reply(String text,LocalDateTime updatedAt) { }
    public record View(Long id,Long productId,String productName,int rating,String comment,List<Long> images,
                       Reply reply,Long orderId,LocalDateTime createdAt,boolean canManageReply) { }
    public record Purchase(Long orderId,Long productId,String productName,int quantity,String status,LocalDateTime createdAt) { }
    public record Eligibility(boolean eligible,String message,View review) { }
    public record Summary(Long productId,long count,double average,List<Long> distribution) { }
    private record Row(Long id,Long account,Long order,Long product,Long store,int rating,String comment,boolean deleted,
                       String name,LocalDateTime created,String reply,Long replyActor,LocalDateTime repliedAt) { }
    private final JdbcTemplate jdbc;private final ContentVisibility visibility;private final ReviewImages images;
    public ReviewService(JdbcTemplate jdbc,ContentVisibility visibility,ReviewImages images) {this.jdbc=jdbc;this.visibility=visibility;this.images=images;}
    private static final String SELECT="""
        SELECT r.*,p.name product_name,x.text reply_text,x.actor_id reply_actor,x.updated_at replied_at
        FROM product_reviews r JOIN products p ON p.id=r.product_id
        LEFT JOIN product_review_replies x ON x.review_id=r.id
        """;
    private List<Row> query(String condition,Object...args) {
        return jdbc.query(SELECT+condition,(r,n)->new Row(r.getLong("id"),r.getLong("account_id"),r.getLong("order_id"),r.getLong("product_id"),r.getLong("store_id"),
                r.getInt("rating"),r.getString("comment"),r.getBoolean("deleted"),r.getString("product_name"),r.getTimestamp("created_at").toLocalDateTime(),
                r.getString("reply_text"),r.getObject("reply_actor",Long.class),r.getTimestamp("replied_at")==null?null:r.getTimestamp("replied_at").toLocalDateTime()),args);
    }
    private Row row(long id,boolean lock) {
        return query(" WHERE r.id=?"+(lock?" FOR UPDATE":""),id).stream().findFirst()
                .orElseThrow(()->BusinessException.notFound("REVIEW_NOT_FOUND","Reseña no disponible."));
    }
    private Row own(long account,long id) {
        var r=row(id,true);if(r.account()!=account || r.deleted()) throw BusinessException.notFound("REVIEW_NOT_FOUND","Reseña no disponible.");return r;
    }
    private Purchase purchase(long account,Long order,Long product,boolean lock) {
        if(order==null || product==null) throw BusinessException.invalid("REVIEW_PURCHASE","Selecciona un producto de tu compra.");
        // Lock the purchase before duplicate detection; a concurrent double click cannot create two reviews.
        if(lock) jdbc.queryForList("SELECT id FROM orders WHERE id=? AND account_id=? FOR UPDATE",Long.class,order,account);
        return jdbc.query("""
            SELECT o.id,p.id product_id,oi.product_name,oi.quantity,o.status,o.created_at
            FROM orders o JOIN order_items oi ON oi.order_id=o.id JOIN products p ON p.id=oi.product_id
            WHERE o.id=? AND o.account_id=? AND p.id=? LIMIT 1
            """,(r,n)->new Purchase(r.getLong("id"),r.getLong("product_id"),r.getString("product_name"),r.getInt("quantity"),r.getString("status"),r.getTimestamp("created_at").toLocalDateTime()),order,account,product)
                .stream().findFirst().orElseThrow(()->BusinessException.notFound("REVIEW_PURCHASE","Compra o producto no disponible."));
    }
    public Eligibility eligibility(long account,Long order,Long product) {
        var p=purchase(account,order,product,false);
        var existing=query(" WHERE r.account_id=? AND r.order_id=? AND r.product_id=? AND r.deleted=false",account,order,product);
        return new Eligibility(p.status().equals("DELIVERED"),p.status().equals("DELIVERED")?"Compra entregada verificada.":"Solo puedes reseñar un pedido entregado.",
                existing.isEmpty()?null:view(existing.getFirst(),true,null));
    }
    private void validate(Input input) {
        if(input==null || input.rating()==null || input.rating()<1 || input.rating()>5)
            throw BusinessException.invalid("REVIEW_RATING","Selecciona una calificación de 1 a 5.");
        if(input.comment()!=null && input.comment().length()>2000) throw BusinessException.invalid("REVIEW_COMMENT","El comentario admite hasta 2000 caracteres.");
    }
    private void delivered(long account,Input input,boolean lock) {
        if(!purchase(account,input.orderId(),input.productId(),lock).status().equals("DELIVERED"))
            throw BusinessException.conflict("REVIEW_NOT_DELIVERED","Solo puedes reseñar un pedido entregado.");
    }
    @Transactional
    public View create(long account,Input input,List<byte[]> uploads) {
        validate(input);delivered(account,input,true);
        if(input.keepImageIds()!=null && !input.keepImageIds().isEmpty()) throw BusinessException.invalid("REVIEW_IMAGES","Las imágenes deben adjuntarse a esta reseña.");
        var prepared=images.prepare(uploads);
        var found=query(" WHERE r.account_id=? AND r.order_id=? AND r.product_id=? FOR UPDATE",account,input.orderId(),input.productId());
        long id;
        if(!found.isEmpty()) {
            var r=found.getFirst();if(!r.deleted()) throw BusinessException.conflict("REVIEW_DUPLICATE","Ya publicaste una reseña para este producto de la compra.");
            id=r.id();jdbc.update("UPDATE product_reviews SET rating=?,comment=?,deleted=false,created_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",input.rating(),text(input.comment()),id);
            jdbc.update("DELETE FROM product_review_replies WHERE review_id=?",id);
        } else {
            jdbc.update("INSERT INTO product_reviews(account_id,order_id,product_id,store_id,rating,comment) SELECT ?,?,?,store_id,?,? FROM orders WHERE id=?",
                    account,input.orderId(),input.productId(),input.rating(),text(input.comment()),input.orderId());
            id=jdbc.queryForObject("SELECT id FROM product_reviews WHERE account_id=? AND order_id=? AND product_id=?",Long.class,account,input.orderId(),input.productId());
        }
        storeImages(id,prepared);return view(row(id,false),true,null);
    }
    @Transactional
    public View edit(long account,long id,Input input,List<byte[]> uploads) {
        var r=own(account,id);validate(input);
        if(!Objects.equals(input.orderId(),r.order()) || !Objects.equals(input.productId(),r.product()))
            throw BusinessException.invalid("REVIEW_PURCHASE","No puedes cambiar el producto ni la compra de una reseña.");
        delivered(account,input,false);
        var old=imageIds(id);var keep=input.keepImageIds()==null?old:input.keepImageIds();
        if(!old.containsAll(keep) || new HashSet<>(keep).size()!=keep.size()) throw BusinessException.invalid("REVIEW_IMAGES","Selecciona solo imágenes de tu reseña.");
        var prepared=images.prepare(uploads);
        if(keep.size()+prepared.size()>3) throw BusinessException.invalid("REVIEW_IMAGES","Adjunta como máximo tres imágenes.");
        jdbc.update("UPDATE product_reviews SET rating=?,comment=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",input.rating(),text(input.comment()),id);
        for(long image:old) if(!keep.contains(image)) jdbc.update("DELETE FROM product_review_images WHERE id=? AND review_id=?",image,id);
        storeImages(id,prepared);return view(row(id,false),true,null);
    }
    private void storeImages(long id,List<ReviewImages.Image> prepared) {
        for(var image:prepared) jdbc.update("INSERT INTO product_review_images(review_id,content_type,sha256,content) VALUES (?,?,?,?)",id,image.type(),image.hash(),image.bytes());
    }
    @Transactional public void delete(long account,long id) {
        own(account,id);jdbc.update("UPDATE product_reviews SET deleted=true,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
        jdbc.update("DELETE FROM product_review_images WHERE review_id=?",id);
    }
    private String text(String value) {return value==null?"":value.trim();}
    private void page(int page) {if(page<0 || page>10000) throw BusinessException.invalid("REVIEW_PAGE","Página inválida.");}
    public List<View> mine(long account,int page) {
        page(page);return query(" WHERE r.account_id=? AND r.deleted=false ORDER BY r.id DESC LIMIT 50 OFFSET ?",account,page*50)
                .stream().map(r->view(r,true,null)).toList();
    }
    public List<View> product(long product,int page) {
        page(page);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id=? AND status NOT IN ('DRAFT','RETIRED')",Integer.class,product)==0
                || visibility.nonVisibleIds(ReportContentType.PUBLICACION).contains(Long.toString(product)))
            throw BusinessException.notFound("PRODUCT_NOT_FOUND","Producto no disponible.");
        var hidden=visibility.nonVisibleIds(ReportContentType.RESENA);
        var args=new ArrayList<Object>();args.add(product);args.addAll(hidden);args.add(page*20);
        return query(" WHERE r.product_id=? AND r.deleted=false"+notHidden(hidden)+" ORDER BY r.id DESC LIMIT 20 OFFSET ?",args.toArray())
                .stream().map(r->view(r,false,null)).toList();
    }
    private String notHidden(Set<String> hidden) {return hidden.isEmpty()?"":" AND r.id NOT IN ("+String.join(",",Collections.nCopies(hidden.size(),"?"))+")";}
    private List<Long> imageIds(long id) {return jdbc.queryForList("SELECT id FROM product_review_images WHERE review_id=? ORDER BY id",Long.class,id);}
    private View view(Row r,boolean privatePurchase,Long actor) {
        boolean hiddenReply=visibility.nonVisibleIds(ReportContentType.RESPUESTA_RESENA).contains(r.id().toString());
        return new View(r.id(),r.product(),r.name(),r.rating(),r.comment(),imageIds(r.id()),
                r.reply()==null || hiddenReply?null:new Reply(r.reply(),r.repliedAt()),privatePurchase?r.order():null,r.created(),actor!=null && (r.replyActor()==null || actor.equals(r.replyActor())));
    }
    public List<View> seller(long store,long actor,Long product,Integer rating,Boolean answered,int page) {
        page(page);if(rating!=null && (rating<1 || rating>5)) throw BusinessException.invalid("REVIEW_RATING","Filtra con una calificación de 1 a 5.");
        var args=new ArrayList<Object>();args.add(store);String where=" WHERE r.store_id=? AND r.deleted=false";
        if(product!=null){where+=" AND r.product_id=?";args.add(product);}
        if(rating!=null){where+=" AND r.rating=?";args.add(rating);}
        if(answered!=null) where+=answered?" AND x.review_id IS NOT NULL":" AND x.review_id IS NULL";
        args.add(page*50);
        return query(where+" ORDER BY r.id DESC LIMIT 50 OFFSET ?",args.toArray()).stream().map(r->view(r,true,actor)).toList();
    }
    private Row sellerOwn(long store,long id) {var r=row(id,true);if(r.deleted() || r.store()!=store) throw BusinessException.notFound("REVIEW_NOT_FOUND","Reseña no disponible.");return r;}
    public Purchase sellerPurchase(long store,long id) {
        var r=row(id,false);if(r.deleted() || r.store()!=store) throw BusinessException.notFound("REVIEW_NOT_FOUND","Reseña no disponible.");
        return purchase(r.account(),r.order(),r.product(),false);
    }
    @Transactional public void reply(long store,long actor,long id,String value,boolean remove) {
        var r=sellerOwn(store,id);
        if(r.replyActor()!=null && r.replyActor()!=actor) throw BusinessException.forbidden("REVIEW_REPLY_OWNER","Solo puedes administrar tu propia respuesta.");
        if(remove) jdbc.update("DELETE FROM product_review_replies WHERE review_id=?",id);
        else {
            if(text(value).isEmpty() || value.length()>2000) throw BusinessException.invalid("REVIEW_REPLY","Escribe una respuesta de 1 a 2000 caracteres.");
            jdbc.update("INSERT INTO product_review_replies(review_id,actor_id,text) VALUES (?,?,?) ON DUPLICATE KEY UPDATE text=VALUES(text),updated_at=CURRENT_TIMESTAMP(6)",id,actor,text(value));
        }
    }
    public Map<Long,Summary> summaries(Long product,Long store) {
        var hidden=visibility.nonVisibleIds(ReportContentType.RESENA);var args=new ArrayList<Object>();
        String where=" WHERE r.deleted=false";
        if(product!=null){where+=" AND r.product_id=?";args.add(product);}
        if(store!=null){where+=" AND r.store_id=?";args.add(store);}
        args.addAll(hidden);where+=notHidden(hidden);
        Map<Long,List<Long>> counts=new LinkedHashMap<>();
        jdbc.query("SELECT r.product_id,r.rating,COUNT(*) quantity FROM product_reviews r"+where+" GROUP BY r.product_id,r.rating",r->{
            var distribution=counts.computeIfAbsent(r.getLong("product_id"),k->new ArrayList<>(List.of(0L,0L,0L,0L,0L)));
            distribution.set(r.getInt("rating")-1,r.getLong("quantity"));
        },args.toArray());
        Map<Long,Summary> result=new LinkedHashMap<>();counts.forEach((id,d)->{
            long count=d.stream().mapToLong(Long::longValue).sum();long total=0;for(int i=0;i<5;i++) total+=(i+1)*d.get(i);
            result.put(id,new Summary(id,count,(double)total/count,List.copyOf(d)));
        });return result;
    }
    public Summary summary(long product) {return summaries(product,null).getOrDefault(product,new Summary(product,0,0,List.of(0L,0L,0L,0L,0L)));}
    public ReviewImages.Image image(long id) {
        var data=jdbc.query("SELECT review_id,content_type,sha256,content FROM product_review_images WHERE id=?",(r,n)->new Object[]{r.getLong(1),r.getString(2),r.getString(3),r.getBytes(4)},id);
        if(data.isEmpty()) throw BusinessException.notFound("REVIEW_IMAGE","Imagen no disponible.");
        var d=data.getFirst();var r=row((Long)d[0],false);
        if(r.deleted() || visibility.nonVisibleIds(ReportContentType.RESENA).contains(r.id().toString())
                || visibility.nonVisibleIds(ReportContentType.PUBLICACION).contains(r.product().toString())
                || jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id=? AND status NOT IN ('DRAFT','RETIRED')",Integer.class,r.product())==0)
            throw BusinessException.notFound("REVIEW_IMAGE","Imagen no disponible.");
        return new ReviewImages.Image((String)d[1],(String)d[2],(byte[])d[3]);
    }
}
