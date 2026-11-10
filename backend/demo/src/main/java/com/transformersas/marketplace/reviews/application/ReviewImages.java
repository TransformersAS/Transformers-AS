package com.transformersas.marketplace.reviews.application;

import com.transformersas.marketplace.shared.files.ImageValidator;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;

/** Validates real bytes before storage; bounds dimensions, strips metadata and resizes once. */
@Component
public class ReviewImages {
    public record Image(String type, String hash, byte[] bytes) { }
    private final ImageValidator validator=new ImageValidator();
    public List<Image> prepare(List<byte[]> files) {
        if(files==null || files.size()>3) throw BusinessException.invalid("REVIEW_IMAGES", "Adjunta como máximo tres imágenes JPG o PNG.");
        return files.stream().map(this::prepare).toList();
    }
    private Image prepare(byte[] bytes) {
        var original=validator.validate(bytes);
        try {
            BufferedImage source=ImageIO.read(new ByteArrayInputStream(bytes));
            double factor=Math.min(1.0,1600.0/Math.max(original.width(),original.height()));
            var resized=new BufferedImage(Math.max(1,(int)(source.getWidth()*factor)),Math.max(1,(int)(source.getHeight()*factor)),
                    original.contentType().equals("image/png")?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
            var graphics=resized.createGraphics();
            try {graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source,0,0,resized.getWidth(),resized.getHeight(),null);
            } finally {graphics.dispose();}
            var out=new ByteArrayOutputStream();ImageIO.write(resized,original.contentType().equals("image/png")?"png":"jpg",out);
            byte[] stored=out.toByteArray();var checked=validator.validate(stored);
            return new Image(checked.contentType(),checked.sha256(),stored);
        } catch(IOException e) {throw BusinessException.invalid("IMAGE_CORRUPT","No se pudo procesar la imagen.");}
    }
}
