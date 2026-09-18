package com.transformersas.marketplace.address;

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
    public List<Address> getAll() {
        return addressService.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Address create(
            @RequestBody Address address
    ) {
        return addressService.create(address);
    }
}