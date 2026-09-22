package com.transformersas.marketplace.stock;

import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CU-15: prueba unitaria de {@link ExcelSheet}, sin arrancar el contexto de Spring. Cubre el caso que
 * {@code SellerExcelTests} no puede provocar con un {@code MultipartFile} en memoria: que leer el archivo falle a
 * mitad de camino (por ejemplo, si el almacenamiento temporal de la subida se pierde).
 */
class ExcelSheetTests {

    @Test
    void unFalloAlLeerElArchivoSeInformaComoArchivoInvalido() {
        MultipartFile file = new MockMultipartFile("file", "productos.xlsx", SellerExcelController.XLSX,
                new byte[] {1}) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("El almacenamiento temporal de la subida falló");
            }
        };

        assertThatThrownBy(() -> ExcelSheet.read(file, ExcelTemplates.PRODUCT_HEADERS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("El archivo no es un Excel (.xlsx) válido");
    }
}
