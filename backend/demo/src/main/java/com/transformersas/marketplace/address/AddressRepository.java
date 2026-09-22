package com.transformersas.marketplace.address;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AddressRepository
        extends JpaRepository<Address, Long> {
    List<Address> findByAccountId(Long accountId);
    Optional<Address> findByIdAndAccountId(Long id, Long accountId);
    boolean existsByIdAndAccountId(Long id, Long accountId);
}