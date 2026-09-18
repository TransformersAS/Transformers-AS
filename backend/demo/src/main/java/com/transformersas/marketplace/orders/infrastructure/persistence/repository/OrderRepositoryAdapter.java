package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderEntity;
import com.transformersas.marketplace.orders.infrastructure.persistence.mapper.OrderMapper;

import org.springframework.stereotype.Repository;

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
}