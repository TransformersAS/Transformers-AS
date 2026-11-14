package com.transformersas.marketplace.address;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AddressRepository
        extends JpaRepository<Address, Long> {
    List<Address> findByAccountId(Long accountId);
    // Creation of orders/checkouts and deletion serialize on the same address row.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Address> findByIdAndAccountId(Long id, Long accountId);
    boolean existsByIdAndAccountId(Long id, Long accountId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Address a set a.defaultAddress=false where a.accountId=:account and a.defaultAddress=true")
    void clearDefault(@org.springframework.data.repository.query.Param("account") Long account);
}
