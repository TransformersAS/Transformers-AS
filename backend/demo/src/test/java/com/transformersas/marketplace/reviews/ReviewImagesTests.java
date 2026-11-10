package com.transformersas.marketplace.reviews;
import com.transformersas.marketplace.reviews.application.ReviewImages;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.io.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ReviewImagesTests {
 byte[] image(String type,int size)throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB),type,out);return out.toByteArray();}
 @Test void jpgAndPngAreDecodedReencodedAndLargeImagesAreReduced() throws Exception {
  for(String format:List.of("jpg","png")){byte[] input=image(format,1800);var result=new ReviewImages().prepare(List.of(input)).getFirst();var decoded=ImageIO.read(new ByteArrayInputStream(result.bytes()));assertThat(decoded.getWidth()).isEqualTo(1600);assertThat(result.hash()).hasSize(64);assertThat(result.type()).startsWith("image/");}
 }
 @Test void rejectsSpoofedOversizedEmptyAndTooManyImages() {
  var service=new ReviewImages();
  for(List<byte[]> files:Arrays.asList(null,List.of(new byte[0]),List.of(new byte[5*1024*1024+1]),List.of("<script>".getBytes()),List.of(new byte[0],new byte[0],new byte[0],new byte[0])))
   assertThatThrownBy(()->service.prepare(files)).isInstanceOf(BusinessException.class);
 }
}
