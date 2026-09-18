package com.transformersas.marketplace.address.dto;

import com.transformersas.marketplace.address.Address;

public record AddressResponse(Long id, String recipientName, String street, String city,
                              String department, String postalCode, String phone) {
    public static AddressResponse from(Address address) {
        return new AddressResponse(address.getId(), address.getRecipientName(), address.getStreet(),
                address.getCity(), address.getDepartment(), address.getPostalCode(), address.getPhone());
    }
}
