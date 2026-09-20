package com.transformersas.marketplace.shared;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.files.ImageValidator;
import com.transformersas.marketplace.shared.files.ValidatedImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RNF-007: el contenido de la imagen se valida antes de guardarla, sin fiarse de la extensión ni del tipo declarado. */
class ImageValidatorTests {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final ImageValidator validator = new ImageValidator();

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            picture.setRGB(x, x % height, 0x336699);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, format, out);
        return out.toByteArray();
    }

    private void assertRejected(byte[] content, String code) {
        assertThatThrownBy(() -> validator.validate(content)).isInstanceOfSatisfying(BusinessException.class,
                error -> {
                    assertThat(error.kind()).isEqualTo(BusinessException.Kind.INVALID);
                    assertThat(error.code()).isEqualTo(code);
                });
    }

    @Test
    void aValidPngIsAcceptedAndDescribedFromItsContent() throws Exception {
        byte[] png = image("png", 40, 30);

        ValidatedImage result = validator.validate(png);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.width()).isEqualTo(40);
        assertThat(result.height()).isEqualTo(30);
        assertThat(result.sizeBytes()).isEqualTo(png.length);
        assertThat(result.sha256()).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));
    }

    @Test
    void aValidJpegIsAcceptedAsJpeg() throws Exception {
        ValidatedImage result = validator.validate(image("jpg", 25, 25));

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.width()).isEqualTo(25);
    }

    @Test
    void emptyContentIsRejected() {
        assertRejected(new byte[0], "IMAGE_EMPTY");
        assertRejected(null, "IMAGE_EMPTY");
    }

    @Test
    void otherFormatsAndNonImagesAreRejectedWhateverTheyClaimToBe() {
        assertRejected("GIF89a....".getBytes(StandardCharsets.US_ASCII), "IMAGE_FORMAT_UNSUPPORTED");
        assertRejected("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8),
                "IMAGE_FORMAT_UNSUPPORTED");
        assertRejected("hola, esto no es una imagen".getBytes(StandardCharsets.UTF_8), "IMAGE_FORMAT_UNSUPPORTED");
        assertRejected(new byte[]{(byte) 0x89, 'P'}, "IMAGE_FORMAT_UNSUPPORTED");
    }

    @Test
    void theSizeLimitIsCheckedBeforeAnythingElse() {
        byte[] justOver = new byte[(int) ImageValidator.DEFAULT_MAX_BYTES + 1];
        System.arraycopy(PNG_SIGNATURE, 0, justOver, 0, PNG_SIGNATURE.length);
        assertRejected(justOver, "IMAGE_TOO_LARGE");

        byte[] atTheLimit = Arrays.copyOf(justOver, (int) ImageValidator.DEFAULT_MAX_BYTES);
        assertRejected(atTheLimit, "IMAGE_CORRUPT"); // pasa el tamaño; falla porque no es una imagen real
    }

    @Test
    void aValidSignatureWithBrokenContentIsCorrupt() throws Exception {
        byte[] png = image("png", 40, 30);
        byte[] jpg = image("jpg", 40, 30);

        assertRejected(Arrays.copyOf(png, 45), "IMAGE_CORRUPT");
        assertRejected(Arrays.copyOf(png, png.length - 30), "IMAGE_CORRUPT");
        assertRejected(Arrays.copyOf(jpg, 30), "IMAGE_CORRUPT");
        byte[] garbage = Arrays.copyOf(PNG_SIGNATURE, 64);
        assertRejected(garbage, "IMAGE_CORRUPT");
    }

    @Test
    void dimensionsAreBoundedBeforeDecodingTheWholeImage() throws Exception {
        ImageValidator small = new ImageValidator(ImageValidator.DEFAULT_MAX_BYTES, 100, 5_000);

        assertThat(small.validate(image("png", 70, 70)).width()).isEqualTo(70); // 4.900 píxeles
        assertThatThrownBy(() -> small.validate(image("png", 101, 1))).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.code()).isEqualTo("IMAGE_DIMENSIONS_TOO_LARGE"));
        assertThatThrownBy(() -> small.validate(image("png", 100, 100))).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.code()).isEqualTo("IMAGE_DIMENSIONS_TOO_LARGE"));
    }

    @Test
    void aDifferentSizeLimitCanBeConfiguredForOtherUseCases() throws Exception {
        ImageValidator tiny = new ImageValidator(50, ImageValidator.DEFAULT_MAX_SIDE, ImageValidator.DEFAULT_MAX_PIXELS);

        assertThatThrownBy(() -> tiny.validate(image("png", 200, 200))).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.code()).isEqualTo("IMAGE_TOO_LARGE"));
    }
}
