package com.transformersas.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Busca una categoría hermana con ese nombre (parentId nulo = entre las principales). */
    Optional<Category> findByParentIdAndNameIgnoreCase(Long parentId, String name);

    boolean existsByParentId(Long parentId);

    boolean existsByParentIdAndActiveTrue(Long parentId);
}
