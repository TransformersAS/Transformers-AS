package com.transformersas.marketplace.address;

import com.transformersas.marketplace.address.dto.AddressRequest;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AddressesIntegrationTests extends AbstractIntegrationTest {
    @Autowired AddressService service;
    @Autowired AddressRepository repository;
    Session buyer,other,seller;
    long account,otherAccount;
    @BeforeEach void accounts() throws Exception {
        buyer=sessionWithRole("address-buyer@example.test","COMPRADOR");
        other=sessionWithRole("address-other@example.test","COMPRADOR");
        seller=sessionWithRole("address-seller@example.test","VENDEDOR");
        account=jdbc.queryForObject("SELECT id FROM user_accounts WHERE email='address-buyer@example.test'",Long.class);
        otherAccount=jdbc.queryForObject("SELECT id FROM user_accounts WHERE email='address-other@example.test'",Long.class);
    }
    AddressRequest input(String name){return new AddressRequest(name,"Calle 1 # 2-3","Bogotá","Cundinamarca","110111","+57 300 123-4567");}
    long create(String name){return service.create(account,input(name)).id();}
    String body(AddressRequest input) throws Exception{return json.writeValueAsString(input);}
    long order(long address,String state){long p=seedProduct(1,"Producto de prueba",5,"100");long id=seedOrder(1,state,p,1,"100");jdbc.update("UPDATE orders SET account_id=?,address_id=?,delivery_street='Dirección histórica' WHERE id=?",account,address,id);return id;}

    @Test void httpLifecyclePersistsEditsDefaultsAndSoftDeletion() throws Exception {
        var response=perform(buyer,post("/api/addresses").contentType("application/json").content(body(input("  Ana  "))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.defaultAddress").value(true)).andExpect(jsonPath("$.recipientName").value("Ana")).andReturn();
        long first=json.readTree(response.getResponse().getContentAsString()).get("id").asLong();long second=create("Oficina");
        perform(buyer,get("/api/addresses")).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].id").value(first));
        perform(buyer,put("/api/addresses/"+second).contentType("application/json").content(body(input("Casa nueva")))).andExpect(status().isOk()).andExpect(jsonPath("$.recipientName").value("Casa nueva"));
        perform(buyer,put("/api/addresses/"+second+"/default")).andExpect(status().isOk()).andExpect(jsonPath("$.defaultAddress").value(true));
        perform(buyer,get("/api/addresses")).andExpect(jsonPath("$[0].id").value(second));
        perform(buyer,delete("/api/addresses/"+second)).andExpect(status().isNoContent());
        perform(buyer,get("/api/addresses")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].defaultAddress").value(true));
        assertThat(repository.existsByIdAndAccountId(second,account)).isFalse();
        assertThat(jdbc.queryForObject("SELECT deleted FROM addresses WHERE id=?",Boolean.class,second)).isTrue();
    }
    @Test void deletionOfOnlyAddressLeavesEmptyBookAndNewFirstBecomesDefault(){long id=create("Casa");service.delete(account,id);assertThat(service.getAll(account)).isEmpty();assertThat(service.create(account,input("Nueva")).defaultAddress()).isTrue();}
    @Test void deletionOfNonDefaultDoesNotChangeDefault(){long first=create("Casa"),second=create("Oficina");service.delete(account,second);assertThat(service.getAll(account).getFirst().id()).isEqualTo(first);assertThat(service.getAll(account).getFirst().defaultAddress()).isTrue();}
    @Test void markingCurrentDefaultAgainIsIdempotent(){long id=create("Casa");service.setDefault(account,id);service.setDefault(account,id);assertThat(service.getAll(account)).hasSize(1);assertThat(service.getAll(account).getFirst().defaultAddress()).isTrue();}
    @Test void addressesNeverLeakAcrossAccountsAndClientCannotChangeOwnership() throws Exception {
        long id=create("Privada");
        perform(other,get("/api/addresses").param("accountId",""+account)).andExpect(content().json("[]"));
        for(long target:new long[]{id,Long.MAX_VALUE}) {
            perform(other,put("/api/addresses/"+target).contentType("application/json").content(body(input("Intruso")))).andExpect(status().isNotFound());
            perform(other,put("/api/addresses/"+target+"/default")).andExpect(status().isNotFound());
            perform(other,delete("/api/addresses/"+target)).andExpect(status().isNotFound());
        }
        perform(buyer,put("/api/addresses/"+id).contentType("application/json").content(body(input("Actualizada")).replace("{","{\"accountId\":"+otherAccount+","))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT account_id FROM addresses WHERE id=?",Long.class,id)).isEqualTo(account);
    }
    @Test void anonymousWrongRoleAndMissingCsrfAreRejected() throws Exception {
        long id=create("Casa");
        mvc.perform(get("/api/addresses")).andExpect(status().isUnauthorized());
        perform(seller,get("/api/addresses")).andExpect(status().isForbidden());
        for(var request:List.of(put("/api/addresses/"+id),put("/api/addresses/"+id+"/default"),delete("/api/addresses/"+id))) {
            mvc.perform(request.cookie(buyer.cookie()).contentType("application/json").content(body(input("Cambio")))).andExpect(status().isForbidden());
        }
        assertThat(service.getAll(account).getFirst().recipientName()).isEqualTo("Casa");
    }
    @Test void requiredFieldsAndFormatsAreValidatedWithoutChangingExistingData() throws Exception {
        long id=create("Original");
        var cases=new ArrayList<AddressRequest>();
        cases.add(new AddressRequest(" ","Calle","Ciudad","Depto","","3001234567"));
        cases.add(new AddressRequest("Ana"," ","Ciudad","Depto","","3001234567"));
        cases.add(new AddressRequest("Ana","Calle"," ","Depto","","3001234567"));
        cases.add(new AddressRequest("Ana","Calle","Ciudad"," ","","3001234567"));
        for(String phone:Arrays.asList(null,"","abc","123","1234567890123456","30012<script>"))cases.add(new AddressRequest("Ana","Calle","Ciudad","Depto","",phone));
        cases.add(new AddressRequest("x".repeat(256),"Calle","Ciudad","Depto","","3001234567"));
        cases.add(new AddressRequest("Ana","Calle","Ciudad","Depto","<script>","3001234567"));
        for(var data:cases)perform(buyer,put("/api/addresses/"+id).contentType("application/json").content(body(data))).andExpect(status().isBadRequest());
        assertThat(service.getAll(account).getFirst().recipientName()).isEqualTo("Original");
    }
    @Test void postalCodeCanBeNullOrEmpty() {
        long id=service.create(account,new AddressRequest("Ana","Calle","Ciudad","Depto",null,"3001234567")).id();
        assertThat(service.getAll(account).getFirst().postalCode()).isNull();
        service.update(account,id,new AddressRequest("Ana","Calle","Ciudad","Depto","","3001234567"));
        assertThat(service.getAll(account).getFirst().postalCode()).isEmpty();
    }
    @Test void activeOrdersBlockDeletionAndTerminalOrdersKeepSnapshot() throws Exception {
        for(String state:List.of("CONFIRMED","IN_PREPARATION","READY_FOR_DISPATCH","PICKED_UP","IN_TRANSIT","DELIVERY_EXCEPTION","DELIVERY_ATTEMPT_FAILED","CANCELLATION_REQUESTED")){
            long address=create(state);long order=order(address,state);
            perform(buyer,delete("/api/addresses/"+address)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADDRESS_IN_USE"));
            service.update(account,address,input("Cambio posterior"));
            assertThat(jdbc.queryForObject("SELECT delivery_street FROM orders WHERE id=?",String.class,order)).isEqualTo("Dirección histórica");
        }
        for(String state:List.of("DELIVERED","CANCELLED","RETURNED_TO_SELLER")){
            long address=create(state);long order=order(address,state);service.delete(account,address);
            assertThat(jdbc.queryForObject("SELECT delivery_street FROM orders WHERE id=?",String.class,order)).isEqualTo("Dirección histórica");
            assertThat(jdbc.queryForObject("SELECT address_id FROM orders WHERE id=?",Long.class,order)).isEqualTo(address);
        }
    }
    @Test void deletedAddressCannotBeEditedSelectedAsDefaultOrUsedForCheckout() throws Exception {
        long id=create("Casa");service.delete(account,id);
        perform(buyer,put("/api/addresses/"+id).contentType("application/json").content(body(input("Otra")))).andExpect(status().isNotFound());
        perform(buyer,put("/api/addresses/"+id+"/default")).andExpect(status().isNotFound());
        perform(buyer,delete("/api/addresses/"+id)).andExpect(status().isNotFound());
        perform(buyer,post("/api/checkout/preview").contentType("application/json").content("{\"addressId\":"+id+",\"shippingMethod\":\"STANDARD\"}")).andExpect(status().isNotFound());
    }
    @Test void pendingCheckoutAndUnresolvedReviewBlockDeletionButExpiredAttemptDoesNot() throws Exception {
        for(String state:List.of("STARTING","PENDING","REJECTED","REVIEW")){
            long id=create(state);String checkout=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO payment_checkouts(id,account_id,snapshot_json,total,status,expires_at,created_at,updated_at) VALUES (?,?,?,100,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 10 MINUTE),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",checkout,account,"{\"addressId\":"+id+"}",state);
            perform(buyer,delete("/api/addresses/"+id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADDRESS_IN_CHECKOUT"));
            jdbc.update("UPDATE payment_checkouts SET cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=?",checkout);service.delete(account,id);
        }
        long id=create("Vencida");jdbc.update("INSERT INTO payment_checkouts(id,account_id,snapshot_json,total,status,expires_at,created_at,updated_at) VALUES (?,?,?,100,'PENDING',DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",UUID.randomUUID().toString(),account,"{\"addressId\":"+id+"}");service.delete(account,id);
    }
    @Test void concurrentDefaultChangesLeaveExactlyOneDefault() throws Exception {
        long a=create("A"),b=create("B");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<Future<?>>();for(int i=0;i<12;i++){long id=i%2==0?a:b;jobs.add(executor.submit(()->service.setDefault(account,id)));}
            for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }
        assertThat(service.getAll(account).stream().filter(x->x.defaultAddress()).count()).isEqualTo(1);
    }
    @Test void accountLockRejectsInvalidIdentityAndMissingAccount(){assertThatThrownBy(()->service.setDefault(null,1L)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->service.create(Long.MAX_VALUE,input("Casa"))).isInstanceOf(RuntimeException.class);}
    @Test void defaultsAreIndependentAcrossBuyersAndDatabaseRejectsTwoForSameBuyer(){
        long first=create("Primera");long second=create("Segunda");service.create(otherAccount,input("Otro comprador"));
        assertThatThrownBy(()->jdbc.update("UPDATE addresses SET is_default=TRUE WHERE id=?",second)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.getAll(account).getFirst().id()).isEqualTo(first);
        assertThat(service.getAll(otherAccount).getFirst().defaultAddress()).isTrue();
    }
    @Test void concurrentCreationsHaveOneDefault() throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<Future<?>>();for(int i=0;i<6;i++){int n=i;jobs.add(executor.submit(()->service.create(account,input("Dirección "+n))));}
            for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }
        assertThat(service.getAll(account)).hasSize(6);assertThat(service.getAll(account).stream().filter(x->x.defaultAddress()).count()).isEqualTo(1);
    }
}
