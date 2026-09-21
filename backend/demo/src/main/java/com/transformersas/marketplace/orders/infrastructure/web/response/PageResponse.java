package com.transformersas.marketplace.orders.infrastructure.web.response;

import com.transformersas.marketplace.orders.domain.model.PageResult;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> from(PageResult<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.content().stream().map(mapper).toList(), page.page(), page.size(),
                page.totalElements(), page.totalPages());
    }
}
