package com.transformersas.marketplace.favorites;

import com.transformersas.marketplace.product.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.List;
import static org.springframework.http.HttpStatus.*;

@Service
public class FavoriteService {
    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final FollowedProductVisibility visibility;
    public FavoriteService(JdbcTemplate jdbc, ProductRepository products,FollowedProductVisibility visibility) { this.jdbc=jdbc; this.products=products;this.visibility=visibility; }
    public record Favorite(Long id, String name, BigDecimal price, int stock, boolean active,
                           String category, List<String> images, List<ProductVariant> variants) { }
    @Transactional(readOnly=true)
    public List<Favorite> list(Long account, int page) {
        if (page < 0 || page > 10000) throw new ResponseStatusException(BAD_REQUEST);
        var ids = jdbc.queryForList("SELECT product_id FROM buyer_favorites WHERE account_id=? ORDER BY created_at DESC,product_id DESC LIMIT 50 OFFSET ?", Long.class, account, page*50);
        var rows = products.findAllById(ids).stream().collect(java.util.stream.Collectors.toMap(Product::getId, p->p));
        var hidden=visibility.hiddenIds();
        return ids.stream().filter(rows::containsKey).map(rows::get).map(p -> !visibility.readable(p,hidden)
            ? new Favorite(p.getId(),"Publicación no disponible",BigDecimal.ZERO,0,false,"",List.of(),List.of())
            : new Favorite(p.getId(),p.getName(),p.getPrice(),p.getStock(),
                Boolean.TRUE.equals(p.getActive()) && p.getStatus()==ProductStatus.ACTIVE,p.getCategory(),List.copyOf(p.getImageUrls()),p.getVariants().stream().filter(java.util.Objects::nonNull).toList())).toList();
    }
    @Transactional
    public void save(Long account, Long product) {
        // Serialize additions for the same account; duplicate saves are idempotent.
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE",Long.class,account);
        Product p=products.findById(product).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Producto inexistente"));
        if (!visibility.readable(p)) throw new ResponseStatusException(NOT_FOUND,"Producto no disponible");
        var ids=ids(account);
        if (ids.size()>=500 && !ids.contains(product)) throw new ResponseStatusException(CONFLICT,"Límite de 500 favoritos");
        jdbc.update("INSERT INTO buyer_favorites(account_id,product_id) VALUES (?,?) ON DUPLICATE KEY UPDATE product_id=VALUES(product_id)",account,product);
    }
    @Transactional
    public void remove(Long account, Long product) {
        jdbc.update("DELETE FROM buyer_favorites WHERE account_id=? AND product_id=?",account,product);
    }
    public List<Long> ids(Long account) {
        return jdbc.queryForList("SELECT product_id FROM buyer_favorites WHERE account_id=? ORDER BY product_id",Long.class,account);
    }
}
