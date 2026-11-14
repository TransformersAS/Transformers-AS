package com.transformersas.marketplace.orders;
import com.transformersas.marketplace.orders.domain.model.*;
import com.transformersas.marketplace.orders.infrastructure.pdf.PdfBoxOrderReceiptGenerator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class OrderProvenancePdfTests {
    private Order order(OrderProvenance provenance,OrderPaymentStatus status) {
        return new Order(1L,1L,OrderStatus.CONFIRMED,status,BigDecimal.TEN,1L,1L,"STANDARD",
            new DeliverySnapshot("Ana","Registrada","Cali","Valle",null,"123"),"reference",LocalDateTime.now(),
            List.of(new OrderItem(1L,"Original",1,BigDecimal.TEN,BigDecimal.TEN)),null,BigDecimal.ZERO,null,provenance);
    }
    private String text(Order order) throws Exception {
        try(var pdf=Loader.loadPDF(new PdfBoxOrderReceiptGenerator().generate(order,ZonedDateTime.now()))){return new PDFTextStripper().getText(pdf);}
    }
    @ParameterizedTest @EnumSource(OrderProvenance.Source.class)
    void pdfDistinguishesOnlyTheSupportedOrigin(OrderProvenance.Source source) throws Exception {
        String pdf=text(order(new OrderProvenance(source,source,source,OrderPaymentStatus.APPROVED,false),OrderPaymentStatus.APPROVED));
        assertThat(pdf).contains("Este comprobante no constituye una factura fiscal","Información histórica no disponible","Costo del envío: $ 0.00");
        if(source==OrderProvenance.Source.UNKNOWN) assertThat(pdf).contains("procedencia histórica no verificada","Dirección de entrega registrada").doesNotContain("incorporado por migración");
        if(source==OrderProvenance.Source.MIGRATED) assertThat(pdf).contains("incorporado por migración","Dirección de entrega registrada").doesNotContain("procedencia histórica no verificada");
        if(source==OrderProvenance.Source.CAPTURED_AT_PURCHASE) assertThat(pdf).contains("Dirección de entrega utilizada").doesNotContain("procedencia histórica no verificada","incorporado por migración");
    }
    @Test void laterStateDoesNotInheritInitialEvidenceAndVerifiedUpdateIsSeparate() throws Exception {
        var initial=OrderProvenance.captured();
        assertThat(text(order(initial,OrderPaymentStatus.REFUNDED))).contains("Estado financiero: procedencia histórica no verificada");
        assertThat(text(order(initial.withVerifiedUpdate(),OrderPaymentStatus.REFUNDED))).contains("Actualización financiera respaldada por una confirmación externa registrada.")
            .doesNotContain("Estado financiero: procedencia histórica no verificada");
    }
}
