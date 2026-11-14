package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import com.transformersas.marketplace.orders.application.usecase.CreateOrderUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderProvenanceIntegrationTests extends MercadoPagoH05TestSupport {
    @Autowired CreateOrderUseCase direct;
    @Autowired MercadoPagoFinancialService financial;
    private String detail(long id) throws Exception {
        return perform(buyer,get("/api/orders/{id}",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    @Test void directPurchaseCapturesProvenanceEvenWhenBreakdownIsUnknown() throws Exception {
        var order=direct.execute(accountId,address,"STANDARD","direct-test",new java.math.BigDecimal("190000"));
        var view=json.readTree(detail(order.orderId()));
        assertThat(view.at("/provenance/deliverySource").asString()).isEqualTo("CAPTURED_AT_PURCHASE");
        assertThat(view.at("/provenance/paymentSource").asString()).isEqualTo("CAPTURED_AT_PURCHASE");
        assertThat(view.at("/provenance/financialUpdateVerified").asBoolean()).isFalse();
        assertThat(view.get("discountAmount").isNull()).isTrue();assertThat(view.get("shippingCost").isNull()).isTrue();
    }
    @Test void hostedSnapshotPrecedesOrderAndSurvivesCurrentAddressAndSellerChanges() throws Exception {
        var a=start();var original=json.readTree((String)checkout(a.id()).get("snapshot_json"));
        jdbc.update("UPDATE addresses SET street='Direccion posterior' WHERE id=?",address);
        jdbc.update("UPDATE stores SET name='Tienda posterior' WHERE id=1");
        payment(a,"456","approved");assertThat(webhook("456")).isEqualTo(200);
        var view=json.readTree(detail(orderId(a.id())));
        assertThat(view.at("/delivery/street").asString()).isEqualTo(original.at("/delivery/street").asString());
        assertThat(view.get("sellerNameSnapshot").asString()).isEqualTo(original.get("sellerNameSnapshot").asString());
        assertThat(view.at("/provenance/sellerSource").asString()).isEqualTo("CAPTURED_AT_PURCHASE");
        assertThat(view.at("/provenance/paymentStatusAtCapture").asString()).isEqualTo("APPROVED");
        assertThat(view.get("discountAmount").decimalValue()).isEqualByComparingTo("0");
    }
    @Test void oldCheckoutSnapshotDoesNotAcquireOptimisticDeliveryOrSellerOrigin() throws Exception {
        var a=start();var data=new HashMap<String,Object>(json.readValue((String)checkout(a.id()).get("snapshot_json"),Map.class));
        data.remove("provenance");jdbc.update("UPDATE payment_checkouts SET snapshot_json=? WHERE id=?",json.writeValueAsString(data),a.id());
        payment(a,"456","approved");webhook("456");var view=json.readTree(detail(orderId(a.id())));
        assertThat(view.at("/provenance/deliverySource").asString()).isEqualTo("UNKNOWN");
        assertThat(view.at("/provenance/sellerSource").asString()).isEqualTo("UNKNOWN");
        assertThat(view.at("/provenance/paymentSource").asString()).isEqualTo("CAPTURED_AT_PURCHASE");
        assertThat(view.at("/provenance/financialUpdateVerified").asBoolean()).isTrue();
    }
    @Test void existingUnknownMetadataIsPreservedAndForeignBuyerCannotReadOrDownload() throws Exception {
        var a=start();payment(a,"456","approved");webhook("456");long id=orderId(a.id());
        jdbc.update("UPDATE orders SET delivery_source='UNKNOWN',seller_source='UNKNOWN',payment_source='UNKNOWN',payment_status_at_capture=NULL WHERE id=?",id);
        assertThat(json.readTree(detail(id)).at("/provenance/deliverySource").asString()).isEqualTo("UNKNOWN");
        byte[] bytes=perform(buyer,get("/api/orders/{id}/receipt",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var pdf=Loader.loadPDF(bytes)){assertThat(new PDFTextStripper().getText(pdf)).contains("procedencia histórica no verificada","Dirección de entrega registrada");}
        var other=sessionWithRole("provenance-other@example.test","COMPRADOR");
        perform(other,get("/api/orders/{id}",id)).andExpect(status().isNotFound());
        perform(other,get("/api/orders/{id}/receipt",id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/{id}/receipt",id)).andExpect(status().isUnauthorized());
    }
    @Test void verifiedH05RefundProvidesCurrentEvidenceWithoutRewritingInitialProvenance() throws Exception {
        var a=start();payment(a,"456","approved");webhook("456");long id=orderId(a.id());
        assertThat(cancel(id)).isEqualTo(200);
        assertThat(json.readTree(detail(id)).at("/provenance/financialUpdateVerified").asBoolean()).isFalse();
        when(provider.refunds("456")).thenReturn(List.of());
        when(provider.requestFullRefund(eq("456"),anyString())).thenAnswer(call->{
            var data=new HashMap<String,Object>(json.convertValue(payments.get("456"),Map.class));data.put("status","refunded");payments.put("456",json.valueToTree(data));
            return json.valueToTree(Map.of("id","900456","payment_id","456","amount",a.total(),"status","approved"));
        });
        financial.recoverBatch();
        var view=json.readTree(detail(id));assertThat(view.get("paymentStatus").asString()).isEqualTo("REFUNDED");
        assertThat(view.at("/provenance/paymentStatusAtCapture").asString()).isEqualTo("APPROVED");
        assertThat(view.at("/provenance/financialUpdateVerified").asBoolean()).isTrue();
        var saved=jdbc.queryForMap("SELECT payment_source,payment_status_at_capture FROM orders WHERE id=?",id);
        assertThat(saved).containsEntry("payment_source","CAPTURED_AT_PURCHASE").containsEntry("payment_status_at_capture","APPROVED");
    }
}
