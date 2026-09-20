package com.transformersas.marketplace.reports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-20: imágenes de evidencia (RF-146, A6) y quién puede verlas. */
class ContentReportEvidenceTests extends ContentReportSupport {
    private Session buyer;
    private Session seller;
    private long product;
    private byte[] png;

    @BeforeEach
    void seed() throws Exception {
        seedStore(2, "Otra tienda");
        seller = sellerOfStore("seller@example.com", 1);
        assignStoreOwner(2, createAccount("otro.vendedor@example.com", "VENDEDOR"));
        product = seedProduct(2, "Lámpara ajena", 5, "10.00");
        buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        png = image("png", 40, 30);
    }

    @Test
    void imagesAreStoredWithTheReportAndListedWithoutTheirContent() throws Exception {
        long reportId = idOf(reportWithImages(buyer, product, "SPAM", evidence("uno.png", png),
                evidence("dos.png", image("jpg", 20, 20))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidenceCount", is(2))), "id");

        assertThat(count("report_evidences")).isEqualTo(2);
        assertThat(count("report_evidence_files")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT file_url FROM report_evidences ORDER BY id", String.class))
                .containsExactly("/api/support/moderation/reports/" + reportId + "/evidences/1",
                        "/api/support/moderation/reports/" + reportId + "/evidences/2");
        assertThat(jdbc.queryForList("SELECT content_type FROM report_evidence_files ORDER BY ordinal",
                String.class)).containsExactly("image/png", "image/jpeg");

        String detail = perform(buyer, get(REPORTS + "/" + reportId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidences", hasSize(2))).andExpect(jsonPath("$.evidences[0].fileName", is("uno.png")))
                .andExpect(jsonPath("$.evidences[0].sizeBytes", is(png.length))).andReturn().getResponse()
                .getContentAsString();
        assertThat(detail).doesNotContain("data").doesNotContain("base64");
    }

    @Test
    void theReporterServesTheirImageWithAnEtagAndGets304WhenItIsUnchanged() throws Exception {
        long reportId = idOf(reportWithImages(buyer, product, "SPAM", evidence("uno.png", png))
                .andExpect(status().isCreated()), "id");

        var served = perform(buyer, get(REPORTS + "/" + reportId + "/evidences/1")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andExpect(header().exists("ETag"))
                .andReturn().getResponse();
        assertThat(served.getContentAsByteArray()).isEqualTo(png);

        perform(buyer, get(REPORTS + "/" + reportId + "/evidences/1").header("If-None-Match",
                served.getHeader("ETag"))).andExpect(status().isNotModified());
        perform(buyer, get(REPORTS + "/" + reportId + "/evidences/2")).andExpect(status().isNotFound());
    }

    @Test
    void evidenceOfSomeoneElsesReportIs404ForOtherUsersAndOwners() throws Exception {
        long reportId = idOf(reportWithImages(buyer, product, "SPAM", evidence("uno.png", png))
                .andExpect(status().isCreated()), "id");
        Session stranger = sessionWithRole("intruso@example.com", "COMPRADOR");

        perform(stranger, get(REPORTS + "/" + reportId + "/evidences/1")).andExpect(status().isNotFound());
        perform(seller, get(REPORTS + "/" + reportId + "/evidences/1")).andExpect(status().isNotFound());
        perform(stranger, get("/api/support/moderation/reports/" + reportId + "/evidences/1"))
                .andExpect(status().isForbidden());
        mvc.perform(get(REPORTS + "/" + reportId + "/evidences/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void supportReadsTheEvidenceThroughItsOwnReadOnlyEndpoint() throws Exception {
        long reportId = idOf(reportWithImages(buyer, product, "SPAM", evidence("uno.png", png))
                .andExpect(status().isCreated()), "id");
        Session agent = sessionWithRole("agente@example.com", "SOPORTE");

        var served = perform(agent, get("/api/support/moderation/reports/" + reportId + "/evidences/1"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png")).andReturn()
                .getResponse();
        assertThat(served.getContentAsByteArray()).isEqualTo(png);
        perform(agent, get("/api/support/moderation/reports/" + reportId + "/evidences/9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void moreThanThreeImagesAreRefusedAndNothingIsCreated() throws Exception {
        reportWithImages(buyer, product, "SPAM", evidence("1.png", png), evidence("2.png", png),
                evidence("3.png", png), evidence("4.png", png)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("EVIDENCE_TOO_MANY"))).andExpect(jsonPath("$.details.field", is("evidences")));
        assertNothingWasCreated();
    }

    @Test
    void anInvalidImageIsRefusedWithTheReasonAndWhichOneItIs() throws Exception {
        reportWithImages(buyer, product, "SPAM", evidence("ok.png", png),
                evidence("notas.png", "esto no es una imagen".getBytes()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("IMAGE_FORMAT_UNSUPPORTED")))
                .andExpect(jsonPath("$.details.field", is("evidences[1]")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Imagen 2")));
        reportWithImages(buyer, product, "SPAM", evidence("vacia.png", new byte[0])).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("IMAGE_EMPTY")));
        byte[] truncated = java.util.Arrays.copyOf(png, png.length / 2);
        reportWithImages(buyer, product, "SPAM", evidence("rota.png", truncated)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("IMAGE_CORRUPT")));
        assertNothingWasCreated();
    }

    @Test
    void anEmptyFilePartFromAFormWithoutFilesIsNotAnImage() throws Exception {
        reportWithImages(buyer, product, "SPAM",
                new MockMultipartFile("evidences", "", "application/octet-stream", new byte[0]))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.evidenceCount", is(0)));
    }

    @Test
    void theFileNameIsReducedToItsBaseNameAndNeverEmpty() throws Exception {
        long reportId = idOf(reportWithImages(buyer, product, "SPAM", evidence("..\\..\\etc/pasaporte.png", png),
                evidence("", png)).andExpect(status().isCreated()), "id");

        assertThat(jdbc.queryForList("SELECT file_name FROM report_evidence_files WHERE report_id = ? ORDER BY ordinal",
                String.class, reportId)).containsExactly("pasaporte.png", "evidencia-2.png");
    }

    private void assertNothingWasCreated() {
        assertThat(count("moderation_cases")).isZero();
        assertThat(count("reports")).isZero();
        assertThat(count("report_evidences")).isZero();
        assertThat(count("report_evidence_files")).isZero();
    }
}
