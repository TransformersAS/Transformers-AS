package com.transformersas.marketplace.messaging;
import com.transformersas.marketplace.shared.files.ImageValidator;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
class Cu10ImagesTests {
    final ImageValidator validator=new ImageValidator();
    @Test void decodesJpegPngAndWebpByContent() throws Exception {
        for(String format:new String[]{"jpeg","png"}) {
            var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),format,out);
            assertThat(validator.validate(out.toByteArray()).contentType()).isEqualTo("image/"+format);
        }
        byte[] webp=Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        assertThat(validator.validate(webp).contentType()).isEqualTo("image/webp");
    }
    @Test void rejectsMaliciousRenamedEmptyCorruptAndOversizeFiles() {
        for(byte[] bytes:new byte[][]{new byte[0],"<svg onload='alert(1)'>".getBytes(),new byte[5242881],
            {(byte)0x89,'P','N','G','\r','\n',0x1a,'\n'},"RIFF0000WEBPinvalid".getBytes()})
            assertThatThrownBy(()->validator.validate(bytes)).isInstanceOf(RuntimeException.class);
    }
    @Test void configuredLimitApplies() throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);
        assertThatThrownBy(()->new ImageValidator(10,6000,16000000).validate(out.toByteArray())).hasMessageContaining("máximo");
        assertThatThrownBy(()->new ImageValidator(5242880,1,1).validate(out.toByteArray())).hasMessageContaining("grande");
    }
}
