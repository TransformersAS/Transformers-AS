package com.transformersas.marketplace.orders.domain.model;

import java.util.List;

/** Página de resultados. */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public int totalPages() {
        return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }
}
