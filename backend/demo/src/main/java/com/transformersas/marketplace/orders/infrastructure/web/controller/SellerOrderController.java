package com.transformersas.marketplace.orders.infrastructure.web.controller;

import com.transformersas.marketplace.orders.application.dto.ListOrdersQuery;
import com.transformersas.marketplace.orders.application.dto.OrderActionCommand;
import com.transformersas.marketplace.orders.application.usecase.GetSellerOrderDetailUseCase;
import com.transformersas.marketplace.orders.application.usecase.ListSellerOrdersUseCase;
import com.transformersas.marketplace.orders.application.usecase.StartPreparationUseCase;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.infrastructure.web.request.EmptyActionRequest;
import com.transformersas.marketplace.orders.infrastructure.web.response.OrderStatusResponse;
import com.transformersas.marketplace.orders.infrastructure.web.response.PageResponse;
import com.transformersas.marketplace.orders.infrastructure.web.response.SellerOrderDetailResponse;
import com.transformersas.marketplace.orders.infrastructure.web.response.SellerOrderSummaryResponse;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Pedidos recibidos por la tienda del vendedor (CU-23). Adaptador HTTP delgado: la tienda y el actor salen de
 * CurrentActorProvider (nunca de la ruta ni del body) y cada endpoint delega en un único caso de uso.
 */
@RestController
@RequestMapping("/api/seller/orders")
public class SellerOrderController {

    private final CurrentActorProvider actor;
    private final ListSellerOrdersUseCase listOrders;
    private final GetSellerOrderDetailUseCase orderDetail;
    private final StartPreparationUseCase startPreparation;

    public SellerOrderController(CurrentActorProvider actor, ListSellerOrdersUseCase listOrders,
                                 GetSellerOrderDetailUseCase orderDetail, StartPreparationUseCase startPreparation) {
        this.actor = actor;
        this.listOrders = listOrders;
        this.orderDetail = orderDetail;
        this.startPreparation = startPreparation;
    }

    @GetMapping
    public PageResponse<SellerOrderSummaryResponse> list(
            @RequestParam(name = "status", required = false) List<OrderStatus> statuses,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long storeId = actor.storeId();
        return PageResponse.from(
                listOrders.execute(new ListOrdersQuery(storeId, statuses, from, to, orderId, page, size)),
                SellerOrderSummaryResponse::from);
    }

    @GetMapping("/{id}")
    public SellerOrderDetailResponse detail(@PathVariable Long id) {
        return SellerOrderDetailResponse.from(orderDetail.execute(id, actor.storeId()));
    }

    @PostMapping("/{id}/start-preparation")
    public OrderStatusResponse startPreparation(@PathVariable Long id,
                                                @RequestBody(required = false) EmptyActionRequest body) {
        return OrderStatusResponse.from(
                startPreparation.execute(new OrderActionCommand(id, actor.storeId(), actor.actorId())));
    }
}
