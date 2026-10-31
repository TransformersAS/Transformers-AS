package com.transformersas.marketplace.notifications.infrastructure.web.controller;

import com.transformersas.marketplace.notifications.application.usecase.ListOwnNotifications;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.auth.infrastructure.security.BuyerAccess;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationController {
    private final ListOwnNotifications query;
    private final CurrentActorProvider seller;
    public NotificationController(ListOwnNotifications query, CurrentActorProvider seller) { this.query=query;this.seller=seller; }
    @GetMapping("/api/notifications")
    public ListOwnNotifications.Page buyer(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return query.execute(RecipientType.BUYER,BuyerAccess.accountId(principal),page,size);
    }
    @GetMapping("/api/seller/notifications")
    public ListOwnNotifications.Page seller(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return query.execute(RecipientType.STORE,seller.storeId(),page,size);
    }
}
