package com.transformersas.marketplace.shared.files;

import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * Validación de imágenes subidas por los usuarios (RNF-007), compartida por todos los casos de uso que las acepten.
 * Se valida el contenido antes de guardar nada: tamaño, formato real (JPEG o PNG, por firma y no por extensión ni por
 * el Content-Type declarado), dimensiones acotadas y que la imagen se decodifique. Una imagen inválida lanza
 * BusinessException INVALID con un código estable (IMAGE_EMPTY, IMAGE_TOO_LARGE, IMAGE_FORMAT_UNSUPPORTED,
 * IMAGE_DIMENSIONS_TOO_LARGE, IMAGE_CORRUPT) y no deja rastro.
 */
@Component
public class ImageValidator {

    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    public static final int DEFAULT_MAX_SIDE = 6000;
    public static final long DEFAULT_MAX_PIXELS = 16_000_000L;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private final long maxBytes;
    private final int maxSide;
    private final long maxPixels;

    public ImageValidator() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_SIDE, DEFAULT_MAX_PIXELS);
    }

    public ImageValidator(long maxBytes, int maxSide, long maxPixels) {
        this.maxBytes = maxBytes;
        this.maxSide = maxSide;
        this.maxPixels = maxPixels;
    }

    public ValidatedImage validate(byte[] content) {
        if (content == null || content.length == 0) {
            throw BusinessException.invalid("IMAGE_EMPTY", "La imagen está vacía");
        }
        if (content.length > maxBytes) {
            throw BusinessException.invalid("IMAGE_TOO_LARGE",
                    "La imagen supera el tamaño máximo de " + maxBytes / (1024 * 1024) + " MB");
        }
        String format;
        String contentType;
        if (startsWith(content, PNG_SIGNATURE)) {
            format = "png";
            contentType = "image/png";
        } else if (startsWith(content, JPEG_SIGNATURE)) {
            format = "jpeg";
            contentType = "image/jpeg";
        } else {
            throw BusinessException.invalid("IMAGE_FORMAT_UNSUPPORTED", "Solo se admiten imágenes JPG y PNG");
        }
        int[] size = decode(content, format);
        return new ValidatedImage(contentType, size[0], size[1], content.length, sha256(content));
    }

    /** Lee las dimensiones antes de decodificar (para no cargar una imagen enorme) y luego decodifica completa. */
    private int[] decode(byte[] content, String format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw BusinessException.invalid("IMAGE_FORMAT_UNSUPPORTED", "Solo se admiten imágenes JPG y PNG");
        }
        ImageReader reader = readers.next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > maxSide || height > maxSide || (long) width * height > maxPixels) {
                throw BusinessException.invalid("IMAGE_DIMENSIONS_TOO_LARGE",
                        "La imagen es demasiado grande: máximo " + maxSide + " píxeles por lado");
            }
            if (reader.read(0) == null) {
                throw corrupt();
            }
            return new int[]{width, height};
        } catch (IOException | RuntimeException unreadable) {
            if (unreadable instanceof BusinessException business) {
                throw business;
            }
            throw corrupt();
        } finally {
            reader.dispose();
        }
    }

    private static BusinessException corrupt() {
        return BusinessException.invalid("IMAGE_CORRUPT", "No se pudo leer la imagen: el archivo está dañado");
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
