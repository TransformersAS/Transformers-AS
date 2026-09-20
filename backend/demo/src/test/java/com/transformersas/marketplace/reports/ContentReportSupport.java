package com.transformersas.marketplace.reports;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Utilidades de las pruebas de CU-20. Las tablas de reportes las limpia cada prueba, antes y después, porque la lista
 * compartida de {@link AbstractIntegrationTest} no las incluye.
 */
abstract class ContentReportSupport extends AbstractIntegrationTest {
    static final String REPORTS = "/api/reports";
    static final String CASES = "/api/support/moderation/cases";

    private static final List<String> REPORT_TABLES = List.of("report_evidence_files", "moderation_notifications",
            "moderation_referrals", "content_moderation_state", "information_requests", "moderation_actions",
            "report_evidences", "reports", "moderation_cases", "audit_logs");

    @BeforeEach
    @AfterEach
    void cleanReportTables() {
        REPORT_TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, format, out);
        return out.toByteArray();
    }

    static String body(String contentId, String reason, String description) {
        return "{\"contentType\":\"PUBLICACION\",\"contentId\":\"%s\",\"reason\":\"%s\",\"description\":\"%s\"}"
                .formatted(contentId, reason, description);
    }

    ResultActions report(Session session, long productId, String reason) throws Exception {
        return perform(session, post(REPORTS).contentType("application/json")
                .content(body(String.valueOf(productId), reason, "El anuncio es engañoso")));
    }

    ResultActions reportWithImages(Session session, long productId, String reason, MockMultipartFile... files)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(REPORTS);
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        // La petición multipart tiene su propio tipo de constructor, así que se firma aquí en vez de con Session.apply.
        return mvc.perform(request.param("contentType", "PUBLICACION")
                .param("contentId", String.valueOf(productId)).param("reason", reason)
                .param("description", "El anuncio es engañoso")
                .cookie(session.cookie()).header(session.csrfHeader(), session.csrfToken()));
    }

    static MockMultipartFile evidence(String name, byte[] content) {
        return new MockMultipartFile("evidences", name, "image/png", content);
    }

    long idOf(ResultActions result, String field) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get(field).asLong();
    }

    long caseOf(long reportId) {
        return jdbc.queryForObject("SELECT case_id FROM reports WHERE id = ?", Long.class, reportId);
    }
}
