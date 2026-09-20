package com.transformersas.marketplace.reports;

import com.transformersas.marketplace.reports.domain.port.ReportEvidenceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-20: un error al radicar no deja casos ni imágenes a medias (A10) y los límites de las imágenes salen de la
 * configuración (RF-146). Usa límites pequeños para no fabricar archivos de 5 MB.
 */
@TestPropertySource(properties = {"reports.evidence.max-count=2", "reports.evidence.max-size=600B"})
class ContentReportAtomicityTests extends ContentReportSupport {
    @MockitoSpyBean ReportEvidenceStorage storage;

    private Session buyer;
    private long product;

    @BeforeEach
    void seed() throws Exception {
        seedStore(2, "Otra tienda");
        assignStoreOwner(2, createAccount("otro.vendedor@example.com", "VENDEDOR"));
        product = seedProduct(2, "Lámpara ajena", 5, "10.00");
        buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        Mockito.reset(storage);
    }

    /** PNG con ruido: no se comprime, así que su tamaño crece con sus dimensiones. */
    private static byte[] noisyPng(int side) throws IOException {
        BufferedImage picture = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) {
                picture.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, "png", out);
        return out.toByteArray();
    }

    @Test
    void aFailureWhileStoringTheSecondImageRollsBackTheCaseTheReportAndTheAudit() throws Exception {
        byte[] small = image("png", 4, 4);
        Mockito.doCallRealMethod().doThrow(new IllegalStateException("disco lleno")).when(storage)
                .save(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt(), Mockito.anyString(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any());

        assertThatThrownBy(() -> reportWithImages(buyer, product, "SPAM", evidence("1.png", small),
                evidence("2.png", small)))
                .hasRootCauseMessage("disco lleno");

        assertThat(count("moderation_cases")).isZero();
        assertThat(count("reports")).isZero();
        assertThat(count("report_evidences")).isZero();
        assertThat(count("report_evidence_files")).isZero();
        assertThat(count("audit_logs")).isZero();

        // El reintento del usuario funciona y no encuentra restos de la petición fallida.
        Mockito.reset(storage);
        reportWithImages(buyer, product, "SPAM", evidence("1.png", small), evidence("2.png", small))
                .andExpect(status().isCreated());
        assertThat(count("reports")).isEqualTo(1);
        assertThat(count("report_evidence_files")).isEqualTo(2);
    }

    @Test
    void theMaximumNumberOfImagesComesFromTheConfiguration() throws Exception {
        byte[] small = image("png", 4, 4);
        reportWithImages(buyer, product, "SPAM", evidence("1.png", small), evidence("2.png", small),
                evidence("3.png", small)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("EVIDENCE_TOO_MANY")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("2 imágenes")));
        assertThat(count("reports")).isZero();
    }

    @Test
    void theMaximumImageSizeComesFromTheConfiguration() throws Exception {
        byte[] big = noisyPng(40);
        assertThat(big.length).isGreaterThan(600);

        reportWithImages(buyer, product, "SPAM", evidence("grande.png", big)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("IMAGE_TOO_LARGE")))
                .andExpect(jsonPath("$.details.field", is("evidences[0]")));
        assertThat(count("reports")).isZero();
    }
}
