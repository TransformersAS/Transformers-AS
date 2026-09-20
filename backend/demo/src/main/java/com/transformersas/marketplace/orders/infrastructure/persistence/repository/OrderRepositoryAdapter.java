package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus;
import com.transformersas.marketplace.orders.domain.model.OrderSearchCriteria;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.model.OrderSummary;
import com.transformersas.marketplace.orders.domain.model.PageResult;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderEntity;
import com.transformersas.marketplace.orders.infrastructure.persistence.mapper.OrderMapper;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Cada método abre (o une) su transacción: el mapeo a dominio recorre colecciones lazy de la entidad. */
@Repository
public class OrderRepositoryAdapter implements OrderRepository {

    private final SpringDataOrderRepository repository;

    public OrderRepositoryAdapter(SpringDataOrderRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Order save(Order order) {
        return OrderMapper.toDomain(repository.save(OrderMapper.newEntity(order)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findById(Long id) {
        return repository.findById(id).map(OrderMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findByIdAndStoreId(Long id, Long storeId) {
        return repository.findByIdAndStoreId(id, storeId).map(OrderMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<OrderSummary> search(OrderSearchCriteria criteria) {
        Specification<OrderEntity> spec = (root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            filters.add(cb.equal(root.get("storeId"), criteria.storeId()));
            if (!criteria.statuses().isEmpty()) {
                filters.add(root.get("status").in(criteria.statuses()));
            }
            if (criteria.createdFrom() != null) {
                filters.add(cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.createdFrom()));
            }
            if (criteria.createdBefore() != null) {
                filters.add(cb.lessThan(root.get("createdAt"), criteria.createdBefore()));
            }
            if (criteria.orderId() != null) {
                filters.add(cb.equal(root.get("id"), criteria.orderId()));
            }
            return cb.and(filters.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(criteria.page(), criteria.size(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<OrderEntity> page = repository.findAll(spec, pageable);
        return new PageResult<>(page.getContent().stream().map(OrderMapper::toSummary).toList(),
                criteria.page(), criteria.size(), page.getTotalElements());
    }

    @Override
    @Transactional
    public boolean transitionStatus(Long orderId, OrderStatus from, OrderStatus to) {
        return repository.transitionStatus(orderId, from, to) == 1;
    }

    @Override
    @Transactional
    public boolean transitionPaymentStatus(Long orderId, OrderPaymentStatus from, OrderPaymentStatus to) {
        return repository.transitionPaymentStatus(orderId, from, to) == 1;
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
