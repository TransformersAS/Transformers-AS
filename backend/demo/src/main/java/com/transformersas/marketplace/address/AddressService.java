package com.transformersas.marketplace.address;

import com.transformersas.marketplace.auth.infrastructure.security.BuyerAccess;
import com.transformersas.marketplace.address.dto.AddressRequest;
import com.transformersas.marketplace.address.dto.AddressResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Comparator;
import org.springframework.jdbc.core.JdbcTemplate;
import com.transformersas.marketplace.shared.error.BusinessException;

@Service
@Transactional(readOnly = true)
public class AddressService {
    private final AddressRepository addressRepository;
    private final JdbcTemplate jdbc;

    public AddressService(AddressRepository addressRepository, JdbcTemplate jdbc) {
        this.addressRepository = addressRepository;
        this.jdbc = jdbc;
    }

    public List<AddressResponse> getAll(Long accountId) {
        return addressRepository.findByAccountId(BuyerAccess.requireAccountId(accountId)).stream()
                .sorted(Comparator.comparing(Address::isDefaultAddress).reversed().thenComparing(Address::getId))
                .map(AddressResponse::from).toList();
    }

    @Transactional
    public AddressResponse create(Long accountId, AddressRequest request) {
        lockAccount(accountId);
        Address address = new Address();
        address.setAccountId(accountId);
        address.setDefaultAddress(addressRepository.findByAccountId(accountId).isEmpty());
        copy(address, request);
        return AddressResponse.from(addressRepository.save(address));
    }

    private void lockAccount(Long account) {
        BuyerAccess.requireAccountId(account);
        if (jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, account).isEmpty())
            throw BusinessException.notFound("ACCOUNT_NOT_FOUND", "Cuenta no disponible");
    }

    private Address own(Long account, Long id) {
        return addressRepository.findByIdAndAccountId(id, account)
                .orElseThrow(() -> BusinessException.notFound("ADDRESS_NOT_FOUND", "Dirección no disponible"));
    }

    private void copy(Address address, AddressRequest request) {
        address.setRecipientName(request.recipientName().trim());
        address.setStreet(request.street().trim());
        address.setCity(request.city().trim());
        address.setDepartment(request.department().trim());
        address.setPostalCode(request.postalCode() == null ? null : request.postalCode().trim());
        address.setPhone(request.phone().trim());
    }

    @Transactional
    public AddressResponse update(Long account, Long id, AddressRequest request) {
        BuyerAccess.requireAccountId(account);
        var address = own(account, id);
        copy(address, request);
        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public AddressResponse setDefault(Long account, Long id) {
        lockAccount(account);
        var address = own(account, id);
        if (address.isDefaultAddress()) return AddressResponse.from(address);
        addressRepository.clearDefault(account);
        // clearDefault detaches the locked entity; update only the flag, without a merge/reload.
        jdbc.update("UPDATE addresses SET is_default=TRUE WHERE id=? AND account_id=? AND deleted=FALSE",id,account);
        address.setDefaultAddress(true);
        return AddressResponse.from(address);
    }

    @Transactional
    public void delete(Long account, Long id) {
        lockAccount(account);
        var address = own(account, id);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE address_id=? AND status NOT IN ('DELIVERED','CANCELLED','RETURNED_TO_SELLER')", Long.class, id) > 0)
            throw BusinessException.conflict("ADDRESS_IN_USE", "No puedes eliminar esta dirección: tiene un pedido en proceso.");
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_checkouts WHERE account_id=?
                AND CAST(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.addressId')) AS UNSIGNED)=?
                AND order_id IS NULL AND cancelled_at IS NULL
                AND (status='REVIEW' OR (status IN ('PENDING','STARTING','REJECTED') AND expires_at>CURRENT_TIMESTAMP(6)))
                """, Long.class, account, id) > 0)
            throw BusinessException.conflict("ADDRESS_IN_CHECKOUT", "Termina o cancela la compra pendiente antes de eliminar esta dirección.");
        boolean wasDefault = address.isDefaultAddress();
        address.setDefaultAddress(false);
        address.setDeleted(true);
        addressRepository.saveAndFlush(address);
        if (wasDefault) addressRepository.findByAccountId(account).stream().min(Comparator.comparing(Address::getId))
                .ifPresent(next -> { next.setDefaultAddress(true); addressRepository.saveAndFlush(next); });
    }
}
