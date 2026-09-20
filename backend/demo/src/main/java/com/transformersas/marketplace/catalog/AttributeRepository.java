package com.transformersas.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttributeRepository extends JpaRepository<Attribute, Long> {

    Optional<Attribute> findByNameIgnoreCase(String name);
}
