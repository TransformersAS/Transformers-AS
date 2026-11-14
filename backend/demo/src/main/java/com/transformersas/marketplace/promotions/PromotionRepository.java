package com.transformersas.marketplace.promotions;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    List<Promotion> findByStoreIdOrderByIdDesc(Long storeId);

    Optional<Promotion> findByIdAndStoreId(Long id, Long storeId);

    Optional<Promotion> findByStoreIdAndCode(Long storeId, String code);
}
