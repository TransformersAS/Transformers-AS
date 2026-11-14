package com.transformersas.marketplace.address;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.auth.infrastructure.security.BuyerAccess;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import com.transformersas.marketplace.address.dto.AddressRequest;
import com.transformersas.marketplace.address.dto.AddressResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/addresses")
@CrossOrigin(origins = "*")
public class AddressController {

    private final AddressService addressService;

    public AddressController(
            AddressService addressService
    ) {
        this.addressService = addressService;
    }

    @GetMapping
    public List<AddressResponse> getAll(@AuthenticationPrincipal AccountPrincipal principal) {
        return addressService.getAll(BuyerAccess.accountId(principal));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody AddressRequest address
    ) {
        return addressService.create(BuyerAccess.accountId(principal), address);
    }

    @PutMapping("/{id}")
    public AddressResponse update(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable Long id,
                                  @Valid @RequestBody AddressRequest address) {
        return addressService.update(BuyerAccess.accountId(principal), id, address);
    }

    @PutMapping("/{id}/default")
    public AddressResponse defaultAddress(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable Long id) {
        return addressService.setDefault(BuyerAccess.accountId(principal), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable Long id) {
        addressService.delete(BuyerAccess.accountId(principal), id);
    }
}
