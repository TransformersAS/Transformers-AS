package com.transformersas.marketplace.address;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AddressService {

    private final AddressRepository addressRepository;

    public AddressService(
            AddressRepository addressRepository
    ) {
        this.addressRepository = addressRepository;
    }

    public List<Address> getAll() {
        return addressRepository.findAll();
    }

    public Address create(Address address) {
        return addressRepository.save(address);
    }
}
