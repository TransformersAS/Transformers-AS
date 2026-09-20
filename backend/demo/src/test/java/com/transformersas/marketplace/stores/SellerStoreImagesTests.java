package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RF-059 y A4, A8: logo y portada validados por contenido, guardados en MySQL y servidos con ETag. */
class SellerStoreImagesTests extends AbstractIntegrationTest {

    private Session seller;

    @BeforeEach
    void setUp() throws Exception {
        seedStore(2, "Otra tienda");
        seller = sellerOfStore("seller@example.com", 1);
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        picture.setRGB(0, 0, 0x336699 + width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, format, out);
        return out.toByteArray();
    }

    private ResultActions upload(Session session, String kind, byte[] content, String contentType) throws Exception {
        var request = multipart(HttpMethod.PUT, "/api/seller/store/images/{kind}", kind)
                .file(new MockMultipartFile("file", "archivo", contentType, content));
        return mvc.perform(request.cookie(session.cookie()).header(session.csrfHeader(), session.csrfToken()));
    }

    private ResultActions upload(String kind, byte[] content) throws Exception {
        return upload(seller, kind, content, "image/png");
    }

    private void setStatus(String status, String reason) {
        jdbc.update("UPDATE stores SET status = ?, status_reason = ? WHERE id = 1", status, reason);
    }

    private List<Map<String, Object>> storedImages() {
        return jdbc.queryForList("SELECT kind, content_type, size_bytes, sha256 FROM store_images WHERE store_id = 1 ORDER BY kind");
    }

    @Test
    void rf059_theSellerUploadsALogoAndItIsDescribedWithoutItsContent() throws Exception {
        byte[] logo = image("png", 40, 40);

        upload("logo", logo).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("LOGO"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(logo.length))
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("/api/stores/1/images/logo?v=")));

        assertThat(storedImages()).hasSize(1);
        perform(seller, get("/api/seller/store")).andExpect(status().isOk()).andExpect(jsonPath("$.images", hasSize(1)))
                .andExpect(jsonPath("$.images[0].kind").value("LOGO"))
                .andExpect(jsonPath("$.images[0].data").doesNotExist());
    }

    @Test
    void theTypeComesFromTheContentNotFromTheDeclaredContentType() throws Exception {
        byte[] jpeg = image("jpg", 30, 30);

        upload(seller, "portada", jpeg, "image/png").andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"));
    }

    @Test
    void logoAndCoverAreIndependentAndReplacingKeepsASingleRecordPerKind() throws Exception {
        upload("logo", image("png", 10, 10)).andExpect(status().isOk());
        upload("portada", image("png", 20, 20)).andExpect(status().isOk());
        byte[] newer = image("png", 33, 33);
        upload("LOGO", newer).andExpect(status().isOk());

        assertThat(storedImages()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM store_images WHERE store_id = 1 AND kind = 'LOGO'",
                Long.class)).isEqualTo(newer.length);
    }

    @Test
    void servedImagesCarryEtagAndCacheControlAndAnUnchangedOneIsNotModified() throws Exception {
        byte[] logo = image("png", 40, 40);
        upload("logo", logo).andExpect(status().isOk());

        MvcResult served = perform(seller, get("/api/stores/1/images/logo")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=3600")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("must-revalidate")))
                .andExpect(header().exists("ETag")).andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(logo);

        String etag = served.getResponse().getHeader("ETag");
        perform(seller, get("/api/stores/1/images/logo").header("If-None-Match", etag))
                .andExpect(status().isNotModified());

        upload("logo", image("png", 41, 41)).andExpect(status().isOk());
        perform(seller, get("/api/stores/1/images/logo").header("If-None-Match", etag)).andExpect(status().isOk());
    }

    @Test
    void anyAuthenticatedUserCanSeeThePublicImagesButAnonymousCannot() throws Exception {
        upload("portada", image("png", 20, 20)).andExpect(status().isOk());
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");

        perform(buyer, get("/api/stores/1/images/portada")).andExpect(status().isOk());
        mvc.perform(get("/api/stores/1/images/portada")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingImagesAndUnknownKindsAreReported() throws Exception {
        perform(seller, get("/api/stores/1/images/logo")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_IMAGE_NOT_FOUND"));
        perform(seller, get("/api/stores/1/images/banner")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_IMAGE_KIND_INVALID"));
        upload("banner", image("png", 10, 10)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_IMAGE_KIND_INVALID"));
    }

    @Test
    void a4_anInvalidImageIsRejectedAndTheValidOnesAreKept() throws Exception {
        byte[] logo = image("png", 40, 40);
        byte[] cover = image("jpg", 60, 30);
        upload("logo", logo).andExpect(status().isOk());
        upload("portada", cover).andExpect(status().isOk());
        var before = storedImages();

        upload("logo", "esto no es una imagen".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_FORMAT_UNSUPPORTED"));
        upload("logo", Arrays.copyOf(logo, 30)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_CORRUPT"));
        upload("logo", new byte[0]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_EMPTY"));
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(logo, 0, tooBig, 0, 8);
        upload("portada", tooBig).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));

        assertThat(storedImages()).isEqualTo(before);
        MvcResult served = perform(seller, get("/api/stores/1/images/logo")).andExpect(status().isOk()).andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(logo);
    }

    @Test
    void anInvalidFirstUploadLeavesTheStoreWithoutImages() throws Exception {
        upload("logo", "GIF89a".getBytes(StandardCharsets.US_ASCII)).andExpect(status().isBadRequest());

        assertThat(storedImages()).isEmpty();
        assertThat(count("audit_events")).isZero();
    }

    @Test
    void a8_aRestrictedStoreCannotChangeItsImages() throws Exception {
        byte[] logo = image("png", 40, 40);
        upload("logo", logo).andExpect(status().isOk());
        var before = storedImages();
        setStatus("RESTRICTED", "Reclamaciones pendientes");

        upload("logo", image("png", 50, 50)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_MODIFICATION_BLOCKED"))
                .andExpect(jsonPath("$.details.reason").value("Reclamaciones pendientes"));
        perform(seller, delete("/api/seller/store/images/logo")).andExpect(status().isForbidden());

        assertThat(storedImages()).isEqualTo(before);
        perform(seller, get("/api/seller/store")).andExpect(status().isOk()).andExpect(jsonPath("$.images", hasSize(1)));
    }

    @Test
    void theSellerRemovesAnImageAndRemovingAMissingOneIsNotFound() throws Exception {
        upload("logo", image("png", 40, 40)).andExpect(status().isOk());

        perform(seller, delete("/api/seller/store/images/logo")).andExpect(status().isNoContent());
        perform(seller, get("/api/stores/1/images/logo")).andExpect(status().isNotFound());
        perform(seller, delete("/api/seller/store/images/logo")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_IMAGE_NOT_FOUND"));
    }

    @Test
    void theAuditRecordsImageChangesWithoutTheContent() throws Exception {
        upload("logo", image("png", 40, 40)).andExpect(status().isOk());
        perform(seller, delete("/api/seller/store/images/logo")).andExpect(status().isNoContent());

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT action, actor_type, entity_id, details FROM audit_events ORDER BY id");
        assertThat(events).extracting(event -> event.get("action"))
                .containsExactly("STORE_IMAGE_UPDATED", "STORE_IMAGE_REMOVED");
        assertThat(events.get(0)).containsEntry("actor_type", "SELLER").containsEntry("entity_id", "1");
        assertThat((String) events.get(0).get("details")).contains("LOGO", "sha256");
    }

    @Test
    void rf062_aBuyerCannotUploadAndASellerOnlyChangesHisOwnStore() throws Exception {
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        upload(buyer, "logo", image("png", 10, 10), "image/png").andExpect(status().isForbidden());

        Session other = sellerOfStore("otro@example.com", 2);
        upload(other, "logo", image("png", 12, 12), "image/png").andExpect(status().isOk());

        assertThat(storedImages()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store_images WHERE store_id = 2", Integer.class)).isEqualTo(1);
    }
}
