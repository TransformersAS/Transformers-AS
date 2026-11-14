package com.transformersas.marketplace.notifications.infrastructure.push;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.users.domain.model.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/push")
public class PushController {
    private final PushSettings settings;private final PushSubscriptionService subscriptions;private final CurrentActorProvider seller;
    public PushController(PushSettings settings,PushSubscriptionService subscriptions,CurrentActorProvider seller){this.settings=settings;this.subscriptions=subscriptions;this.seller=seller;}
    @GetMapping("/config") public Map<String,Object> config(){return Map.of("enabled",settings.ready(),"publicKey",settings.ready()?settings.publicKey():"");}
    @PostMapping("/subscriptions") public Map<String,String> subscribe(@AuthenticationPrincipal AccountPrincipal principal,
            HttpServletRequest request,@RequestBody PushSubscriptionService.Input input) {
        if(principal==null) throw new ResponseStatusException(UNAUTHORIZED);
        boolean buyer=principal.activeRole()==Role.COMPRADOR;
        if(!buyer && principal.activeRole()!=Role.VENDEDOR) throw new ResponseStatusException(FORBIDDEN);
        String id=subscriptions.save(principal.accountId(),buyer?"BUYER":"STORE",buyer?principal.accountId():seller.storeId(),request.getSession().getId(),input);
        return Map.of("id",id);
    }
    public record Removal(String endpoint) { @Override public String toString(){return "Removal[redacted]";} }
    @PostMapping("/status") public Map<String,Boolean> status(@AuthenticationPrincipal AccountPrincipal p,HttpServletRequest request,@RequestBody Removal input) {
        boolean buyer=p.activeRole()==Role.COMPRADOR;
        return Map.of("registered",subscriptions.registered(p.accountId(),request.getSession().getId(),buyer?"BUYER":"STORE",buyer?p.accountId():seller.storeId(),input.endpoint()));
    }
    @DeleteMapping("/subscriptions") public void remove(@AuthenticationPrincipal AccountPrincipal principal,@RequestBody Removal input) {
        if(principal==null) throw new ResponseStatusException(UNAUTHORIZED);
        subscriptions.remove(principal.accountId(),input.endpoint());
    }
}
