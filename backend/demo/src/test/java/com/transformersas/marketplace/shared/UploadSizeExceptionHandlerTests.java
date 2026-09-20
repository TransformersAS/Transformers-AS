package com.transformersas.marketplace.shared;

import com.transformersas.marketplace.shared.files.UploadSizeExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/** Una subida que Spring corta por tamaño responde 413 con el mismo código que ImageValidator. */
class UploadSizeExceptionHandlerTests {

    @Test
    void anOversizedUploadRespondsPayloadTooLargeWithTheImageCode() {
        var request = new MockHttpServletRequest("PUT", "/api/seller/store/images/logo");
        request.setRequestURI("/api/seller/store/images/logo");

        var response = new UploadSizeExceptionHandler().tooLarge(request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(response.getBody().status()).isEqualTo(413);
        assertThat(response.getBody().path()).isEqualTo("/api/seller/store/images/logo");
        assertThat(new MaxUploadSizeExceededException(5_242_880L)).isNotNull();
    }
}
