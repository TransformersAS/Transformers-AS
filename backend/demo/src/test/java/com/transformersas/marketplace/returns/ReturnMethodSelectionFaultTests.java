package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.logistics.application.dto.RegisterReturnShipmentCommand;
import com.transformersas.marketplace.logistics.application.usecase.RegisterReturnShipmentUseCase;
import com.transformersas.marketplace.logistics.domain.model.ReturnRequestData;
import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La llamada a logística al elegir el método de retorno: va fuera de toda transacción de base de datos, lleva lo que el
 * contrato pide (la tienda solo por id y la dirección del pedido como recogida) y, si el retorno se creó pero el registro
 * local falla, reintentar con la misma clave no duplica el retorno.
 */
class ReturnMethodSelectionFaultTests extends ReturnsTestSupport {
    @MockitoSpyBean SimulatedLogisticsGateway gateway;
    @MockitoSpyBean RegisterReturnShipmentUseCase register;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    private Session buyer;
    private Session seller;
    private long buyerId;

    @BeforeEach
    void seed() throws Exception {
        Mockito.reset(gateway, register);
        gateway.reset();
        seller = sellerOfStore("vendedor@example.com", 1);
        buyer = sessionWithRole("comprador@example.com", "COMPRADOR");
        buyerId = accountIdOf("comprador@example.com");
    }

    private long approved() throws Exception {
        DeliveredOrder order = deliveredOrder(buyerId, LocalDateTime.now().minusDays(3));
        long id = idOf(requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "No enciende")
                .andExpect(status().isCreated()), "id");
        perform(seller, post(SELLER_RETURNS + "/" + id + "/review")).andExpect(status().isOk());
        perform(seller, post(SELLER_RETURNS + "/" + id + "/approve")).andExpect(status().isOk());
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions choose(long id, String method) throws Exception {
        return perform(buyer, post(RETURNS + "/" + id + "/return-method").contentType("application/json")
                .content("{\"method\":\"" + method + "\"}"));
    }

    @Test
    void theExternalCallToLogisticsRunsOutsideAnyDatabaseTransaction() throws Exception {
        long id = approved();
        List<Boolean> insideTransaction = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            insideTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(gateway).createReturn(Mockito.any(), Mockito.anyString());
        Mockito.doAnswer(invocation -> {
            insideTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(gateway).fetchReturnMethods(Mockito.anyLong(), Mockito.anyLong());

        perform(buyer, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(RETURNS + "/" + id + "/return-methods")).andExpect(status().isOk());
        choose(id, "PICKUP").andExpect(status().isOk());

        assertThat(insideTransaction).containsExactly(false, false);
        // Y la parte local sí es transaccional: el registro del seguimiento se une a la transacción de la elección.
        Mockito.verify(gateway, Mockito.times(1)).createReturn(Mockito.any(), Mockito.anyString());
    }

    @Test
    void logisticsReceivesTheStoreByIdTheOrdersDeliveryAddressAsPickupAndTheReturnKey() throws Exception {
        long id = approved();

        choose(id, "DROP_OFF").andExpect(status().isOk());

        ArgumentCaptor<ReturnRequestData> data = ArgumentCaptor.forClass(ReturnRequestData.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        Mockito.verify(gateway).createReturn(data.capture(), key.capture());
        assertThat(key.getValue()).isEqualTo("return-" + id);
        assertThat(data.getValue()).satisfies(request -> {
            assertThat(request.returnId()).isEqualTo(id);
            assertThat(request.storeId()).isEqualTo(1L);
            assertThat(request.methodCode()).isEqualTo("DROP_OFF");
            assertThat(request.pickup().street()).isEqualTo("Calle 1 # 2-3");
            assertThat(request.pickup().city()).isEqualTo("Bogotá");
            assertThat(request.pickup().postalCode()).isEqualTo("110111");
            assertThat(request.items()).singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
        });
    }

    @Test
    void ifTheReturnWasCreatedButRegisteringTheTrackingFailsNothingLocalRemainsAndTheRetryDoesNotDuplicateTheReturn()
            throws Exception {
        long id = approved();
        Mockito.doThrow(new IllegalStateException("falla el registro del envío")).doCallRealMethod().when(register)
                .execute(Mockito.any(RegisterReturnShipmentCommand.class));

        assertThatThrownBy(() -> choose(id, "PICKUP")).hasRootCauseMessage("falla el registro del envío");

        // En logística el retorno ya existe; aquí no quedó nada elegido ni registrado y la devolución sigue Aprobada.
        assertThat(gateway.distinctReturns()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests", String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT return_method_code FROM return_requests", String.class)).isNull();
        assertThat(count("return_shipments")).isZero();
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events WHERE event_type = 'METHOD_CHOSEN'",
                String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'RETURN_METHOD_CHOSEN'",
                Integer.class)).isZero();

        choose(id, "PICKUP").andExpect(status().isOk()).andExpect(jsonPath("$.returnMethodCode", is("PICKUP")));

        assertThat(gateway.distinctReturns()).isEqualTo(1); // la misma clave: logística devolvió el mismo retorno
        Mockito.verify(gateway, Mockito.times(2)).createReturn(Mockito.any(), Mockito.eq("return-" + id));
        assertThat(count("return_shipments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT provider_return_id FROM return_shipments", String.class))
                .isEqualTo("SIM-return-" + id);
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events WHERE event_type = 'METHOD_CHOSEN'",
                String.class)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_tracking_events WHERE provider_event_id = ?",
                Integer.class, "registered-" + id)).isEqualTo(1);
    }
}
