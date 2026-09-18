package com.transformersas.marketplace.address;

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
    public List<AddressResponse> getAll() {
        return addressService.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(
            @Valid @RequestBody AddressRequest address
    ) {
        return addressService.create(address);
    }
}