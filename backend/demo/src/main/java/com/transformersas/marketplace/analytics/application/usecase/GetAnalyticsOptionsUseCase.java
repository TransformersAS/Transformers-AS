package com.transformersas.marketplace.analytics.application.usecase;

import com.transformersas.marketplace.analytics.domain.model.AnalyticsOptions;
import com.transformersas.marketplace.analytics.domain.repository.SalesDataQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Productos y categorías de la tienda para armar los filtros (flujo principal, paso 2). */
@Service
public class GetAnalyticsOptionsUseCase {

    private final SalesDataQuery sales;

    public GetAnalyticsOptionsUseCase(SalesDataQuery sales) {
        this.sales = sales;
    }

    @Transactional(readOnly = true)
    public AnalyticsOptions execute(Long storeId) {
        return sales.options(storeId);
    }
}
