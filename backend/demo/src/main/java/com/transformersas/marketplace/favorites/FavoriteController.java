package com.transformersas.marketplace.favorites;

import com.transformersas.marketplace.auth.infrastructure.security.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {
    private final FavoriteService favorites;
    private final ProductAlertService alerts;
    public FavoriteController(FavoriteService favorites,ProductAlertService alerts) { this.favorites=favorites;this.alerts=alerts; }
    @GetMapping public List<FavoriteService.Favorite> list(@AuthenticationPrincipal AccountPrincipal p,@RequestParam(defaultValue="0") int page) { return favorites.list(BuyerAccess.accountId(p),page); }
    @GetMapping("/ids") public List<Long> ids(@AuthenticationPrincipal AccountPrincipal p) { return favorites.ids(BuyerAccess.accountId(p)); }
    @PutMapping("/{productId}") public void save(@AuthenticationPrincipal AccountPrincipal p,@PathVariable Long productId) { favorites.save(BuyerAccess.accountId(p),productId); }
    @DeleteMapping("/{productId}") public void remove(@AuthenticationPrincipal AccountPrincipal p,@PathVariable Long productId) { favorites.remove(BuyerAccess.accountId(p),productId); }
    @GetMapping("/alerts") public List<ProductAlertService.Alert> alerts(@AuthenticationPrincipal AccountPrincipal p) { return alerts.list(BuyerAccess.accountId(p)); }
    @PostMapping("/alerts") public ProductAlertService.Result create(@AuthenticationPrincipal AccountPrincipal p,@RequestBody ProductAlertService.Input input) { return alerts.create(BuyerAccess.accountId(p),input); }
    @PutMapping("/alerts/{id}") public ProductAlertService.Result edit(@AuthenticationPrincipal AccountPrincipal p,@PathVariable String id,@RequestBody ProductAlertService.Input input) { return alerts.edit(BuyerAccess.accountId(p),id,input); }
    public record State(String status) { }
    @PatchMapping("/alerts/{id}") public void status(@AuthenticationPrincipal AccountPrincipal p,@PathVariable String id,@RequestBody State input) { alerts.changeStatus(BuyerAccess.accountId(p),id,input.status()); }
    @DeleteMapping("/alerts/{id}") public void delete(@AuthenticationPrincipal AccountPrincipal p,@PathVariable String id) { alerts.changeStatus(BuyerAccess.accountId(p),id,"DELETED"); }
}
