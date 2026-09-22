package com.transformersas.marketplace.stores;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import com.transformersas.marketplace.stores.domain.model.*;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StoreBoundaryIntegrationTests extends AbstractIntegrationTest {
    @Autowired StoreRepository stores;
    Session seller;
    @BeforeEach void owner() throws Exception { seller=sellerOfStore("boundaries@example.test",1); }
    @Test void invalidProfileTextIsRejectedWithoutChangingPersistedStore() throws Exception {
        for(var invalid:List.of(Map.of("name","x".repeat(101)),Map.of("name","bad\u0000name"),
                Map.of("description","bad\u0000description"),Map.of("businessHours","bad\u0000hours"),
                Map.of("policyText","x".repeat(2001)),Map.of("policyText","bad\u0000policy"))) {
            var body=new LinkedHashMap<String,Object>();
            body.put("name","Valid"); body.put("version",0); body.put("returnWindowDays",30); body.put("shippingMethods",List.of("STANDARD"));
            body.putAll(invalid);
            perform(seller,put("/api/seller/store").contentType("application/json").content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
            assertThat(stores.findById(1L).orElseThrow().profile().name()).isEqualTo("Tienda principal");
        }
    }
    @Test void oversizedDecodedImageIsRejectedBeforePersistence() throws Exception {
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6001,1,BufferedImage.TYPE_INT_RGB),"png",out);
        var req=multipart(HttpMethod.PUT,"/api/seller/store/images/logo")
                .file(new MockMultipartFile("file","wide.png","image/png",out.toByteArray()));
        mvc.perform(req.cookie(seller.cookie()).header(seller.csrfHeader(),seller.csrfToken())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_DIMENSIONS_TOO_LARGE"));
        assertThat(stores.findImage(1L,StoreImageKind.LOGO)).isEmpty();
    }
    @Test void persistedImageIdentityDoesNotDependOnArrayObjectIdentityOrExposeBinary() throws Exception {
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);
        mvc.perform(multipart(HttpMethod.PUT,"/api/seller/store/images/logo")
                .file(new MockMultipartFile("file","logo.png","image/png",out.toByteArray()))
                .cookie(seller.cookie()).header(seller.csrfHeader(),seller.csrfToken())).andExpect(status().isOk());
        var first=stores.findImage(1L,StoreImageKind.LOGO).orElseThrow();
        var second=stores.findImage(1L,StoreImageKind.LOGO).orElseThrow();
        assertThat(first.data()).isNotSameAs(second.data()).containsExactly(second.data());
        assertThat(new HashSet<>(List.of(first,second))).hasSize(1);
        assertThat(first.toString()).contains("LOGO","image/png").doesNotContain(first.sha256(),"[B@");
    }
}
