package com.transformersas.marketplace.stores.infrastructure.persistence.repository;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StorePolicy;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador JDBC de tiendas. La unicidad del nombre y de la dueña la garantiza la BD (UNIQUE, sin distinguir
 * mayúsculas ni tildes), y la edición concurrente se detecta con UPDATE condicionado por la versión.
 */
@Repository
public class JdbcStoreRepository implements StoreRepository {

    private static final String SELECT_STORE =
            "SELECT id, owner_account_id, name, description, contact_email, contact_phone, business_hours,"
                    + " return_window_days, policy_text, status, status_reason, version FROM stores";

    private final JdbcClient jdbc;

    public JdbcStoreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Store> findById(Long id) {
        return jdbc.sql(SELECT_STORE + " WHERE id = ?").param(id).query(this::toStore).optional();
    }

    @Override
    public Optional<Store> findByOwnerAccountId(Long accountId) {
        return jdbc.sql(SELECT_STORE + " WHERE owner_account_id = ?").param(accountId).query(this::toStore).optional();
    }

    @Override
    public boolean existsByName(String name, Long excludingStoreId) {
        Integer count = excludingStoreId == null
                ? jdbc.sql("SELECT COUNT(*) FROM stores WHERE name = ?").param(name).query(Integer.class).single()
                : jdbc.sql("SELECT COUNT(*) FROM stores WHERE name = ? AND id <> ?")
                        .params(name, excludingStoreId).query(Integer.class).single();
        return count > 0;
    }

    @Override
    public Store save(Store store) {
        int updated;
        try {
            StoreProfile profile = store.profile();
            updated = jdbc.sql("""
                            UPDATE stores SET name = ?, description = ?, contact_email = ?, contact_phone = ?,
                                              business_hours = ?, return_window_days = ?, policy_text = ?,
                                              version = version + 1
                            WHERE id = ? AND version = ?""")
                    .params(profile.name(), profile.description(), profile.contactEmail(), profile.contactPhone(),
                            profile.businessHours(), store.policy().returnWindowDays(), store.policy().text(),
                            store.id(), store.version())
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.conflict("STORE_NAME_TAKEN", "Ya existe otra tienda con ese nombre");
        }
        if (updated == 0) {
            findById(store.id()).orElseThrow(
                    () -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe"));
            throw BusinessException.conflict("STORE_CONCURRENT_UPDATE",
                    "La tienda se modificó desde que la consultó; vuelva a cargarla e intente de nuevo");
        }
        return findById(store.id()).orElseThrow();
    }

    @Override
    public Store insert(Long ownerAccountId, StoreProfile profile) {
        var keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO stores (name, description, owner_account_id) VALUES (?, ?, ?)")
                    .params(profile.name(), profile.description(), ownerAccountId).update(keys);
        } catch (DuplicateKeyException duplicate) {
            throw String.valueOf(duplicate.getMessage()).contains("uk_stores_owner")
                    ? BusinessException.conflict("STORE_OWNER_ALREADY_HAS_STORE", "La cuenta ya es dueña de otra tienda")
                    : BusinessException.conflict("STORE_NAME_TAKEN", "Ya existe otra tienda con ese nombre");
        } catch (DataIntegrityViolationException integrity) {
            if (String.valueOf(integrity.getMessage()).contains("fk_stores_owner")) {
                throw BusinessException.invalid("STORE_OWNER_NOT_FOUND", "La cuenta dueña no existe");
            }
            throw integrity;
        }
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    @Override
    public List<String> findShippingMethods(Long storeId) {
        return jdbc.sql("SELECT method FROM store_shipping_methods WHERE store_id = ? ORDER BY method")
                .param(storeId).query(String.class).list();
    }

    @Override
    @Transactional
    public void replaceShippingMethods(Long storeId, Collection<String> methods) {
        jdbc.sql("DELETE FROM store_shipping_methods WHERE store_id = ?").param(storeId).update();
        methods.stream().distinct().forEach(method ->
                jdbc.sql("INSERT INTO store_shipping_methods (store_id, method) VALUES (?, ?)")
                        .params(storeId, method).update());
    }

    @Override
    public boolean assignOwner(Long storeId, Long accountId) {
        try {
            return jdbc.sql("UPDATE stores SET owner_account_id = ? WHERE id = ? AND owner_account_id IS NULL")
                    .params(accountId, storeId).update() == 1;
        } catch (DuplicateKeyException accountAlreadyOwnsAStore) {
            return false;
        }
    }

    private Store toStore(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Store(rs.getLong("id"), rs.getObject("owner_account_id", Long.class),
                new StoreProfile(rs.getString("name"), rs.getString("description"), rs.getString("contact_email"),
                        rs.getString("contact_phone"), rs.getString("business_hours")),
                new StorePolicy(rs.getInt("return_window_days"), rs.getString("policy_text")),
                StoreStatus.valueOf(rs.getString("status")), rs.getString("status_reason"), rs.getLong("version"));
    }
}
