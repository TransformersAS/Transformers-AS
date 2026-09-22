package com.transformersas.marketplace.address;

import com.transformersas.marketplace.auth.infrastructure.security.BuyerAccess;
import com.transformersas.marketplace.address.dto.AddressRequest;
import com.transformersas.marketplace.address.dto.AddressResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class AddressService {
    private final AddressRepository addressRepository;

    public AddressService(AddressRepository addressRepository) {
        this.addressRepository = addressRepository;
    }

    public List<AddressResponse> getAll(Long accountId) {
        return addressRepository.findByAccountId(BuyerAccess.requireAccountId(accountId)).stream().map(AddressResponse::from).toList();
    }

    @Transactional
    public AddressResponse create(Long accountId, AddressRequest request) {
        Address address = new Address();
        address.setAccountId(BuyerAccess.requireAccountId(accountId));
        address.setRecipientName(request.recipientName());
        address.setStreet(request.street());
        address.setCity(request.city());
        address.setDepartment(request.department());
        address.setPostalCode(request.postalCode());
        address.setPhone(request.phone());
        return AddressResponse.from(addressRepository.save(address));
    }
}
