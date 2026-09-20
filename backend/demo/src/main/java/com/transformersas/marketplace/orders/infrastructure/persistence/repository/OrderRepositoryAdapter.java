package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderEntity;
import com.transformersas.marketplace.orders.infrastructure.persistence.mapper.OrderMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepositoryAdapter
        implements OrderRepository {

    private final SpringDataOrderRepository repository;


    public OrderRepositoryAdapter(
            SpringDataOrderRepository repository
    ) {

        this.repository = repository;
    }


    @Override
    public Order save(
            Order order
    ) {

        OrderEntity entity =
                OrderMapper.toEntity(
                        order
                );


        OrderEntity saved =
                repository.save(
                        entity
                );


        return OrderMapper.toDomain(
                saved
        );
    }
    @Override
    @Transactional(readOnly = true)
    public List<Order> findByAccountId(Long accountId) {
        if (accountId == null) return List.of();
        return repository.findByAccountIdOrderByCreatedAtDescIdDesc(accountId).stream()
                .map(OrderMapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findByIdAndAccountId(Long id, Long accountId) {
        if (accountId == null) return Optional.empty();
        return repository.findByIdAndAccountId(id, accountId).map(OrderMapper::toDomain);
    }
    @Override
    @Transactional
    public boolean requestCancellation(Long id, Long accountId) {
        return accountId != null && repository.requestCancellation(id, accountId) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByIdAndAccountId(Long id, Long accountId) {
        return accountId != null && repository.existsByIdAndAccountId(id, accountId);
    }
}