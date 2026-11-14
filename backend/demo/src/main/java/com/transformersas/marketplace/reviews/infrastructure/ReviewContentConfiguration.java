package com.transformersas.marketplace.reviews.infrastructure;
import com.transformersas.marketplace.reports.domain.port.*;
import com.transformersas.marketplace.reports.domain.model.*;
import com.transformersas.marketplace.reports.application.ContentVisibility;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Adapts reviews and replies to the existing CU-20/21 moderation contracts. */
@Configuration(proxyBeanMethods=false)
public class ReviewContentConfiguration {
    @Bean Adapter reviewContent(JdbcTemplate jdbc,ContentVisibility visibility) {return new Adapter(jdbc,visibility,ReportContentType.RESENA);}
    @Bean Adapter reviewReplyContent(JdbcTemplate jdbc,ContentVisibility visibility) {return new Adapter(jdbc,visibility,ReportContentType.RESPUESTA_RESENA);}
    public static class Adapter implements ContentSnapshotProvider,ContentOwnerResolver,ReportableContentVerifier {
        private final JdbcTemplate jdbc;private final ContentVisibility visibility;private final ReportContentType type;
        public Adapter(JdbcTemplate jdbc,ContentVisibility visibility,ReportContentType type) {this.jdbc=jdbc;this.visibility=visibility;this.type=type;}
        @Override public ReportContentType type() {return type;}
        private Long parse(String id) {try {long number=Long.parseLong(id);return number>0?number:null;}catch(RuntimeException e){return null;}}
        @Override public Optional<ContentSnapshot> load(String id) {
            Long number=parse(id);if(number==null) return Optional.empty();
            String sql=type==ReportContentType.RESENA?
                    "SELECT r.comment text,r.account_id owner,p.name title FROM product_reviews r JOIN products p ON p.id=r.product_id WHERE r.id=?":
                    "SELECT x.text,x.actor_id owner,p.name title FROM product_review_replies x JOIN product_reviews r ON r.id=x.review_id JOIN products p ON p.id=r.product_id WHERE r.id=?";
            return jdbc.query(sql,(r,n)->new ContentSnapshot(r.getString("title"),r.getString("text"),r.getString("owner"),Map.of("reviewId",id)),number).stream().findFirst();
        }
        @Override public Optional<String> ownerAccountId(String id) {return load(id).map(ContentSnapshot::ownerId);}
        @Override public boolean isAccessibleTo(String id,String reporterId) {
            if(parse(id)==null || visibility.stateOf(type,id)!=ContentModerationState.VISIBLE || load(id).isEmpty()) return false;
            var products=jdbc.query("SELECT p.id FROM product_reviews r JOIN products p ON p.id=r.product_id WHERE r.id=? AND r.deleted=false AND p.status NOT IN ('DRAFT','RETIRED')",(r,n)->r.getString(1),parse(id));
            return !products.isEmpty()
                    && visibility.stateOf(ReportContentType.PUBLICACION,products.getFirst())==ContentModerationState.VISIBLE
                    && (type!=ReportContentType.RESPUESTA_RESENA || visibility.stateOf(ReportContentType.RESENA,id)==ContentModerationState.VISIBLE);
        }
    }
}
