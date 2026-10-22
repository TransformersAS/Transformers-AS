package com.transformersas.marketplace.product;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductDetailIntegrationTests extends AbstractIntegrationTest {
    // Spring Session escribe con una transaccion independiente. La preparacion
    // de estas pruebas debe confirmarse antes de enviar peticiones HTTP.
    @AfterEach
    void cleanDetailFixture() {
        resetDatabase();
        jdbc.update("DELETE FROM attribute_values WHERE attribute_id IN (SELECT id FROM attributes WHERE name='Potencia ficha CU01')");
        jdbc.update("DELETE FROM attributes WHERE name='Potencia ficha CU01'");
        jdbc.update("DELETE FROM brands WHERE name='Marca ficha CU01'");
    }

    @Test
    void buyerReceivesStoredImagesBrandSpecificationsVariantsAndPublicStore() throws Exception {
        var buyer = sessionWithRole("ficha@example.com", "COMPRADOR");
        long id = seedProduct(1, "Lampara ficha", 16, "55000");
        jdbc.update("INSERT INTO brands(name,active) VALUES ('Marca ficha CU01',TRUE)");
        jdbc.update("UPDATE products SET description='Para escritorio y lectura', brand_id=(SELECT id FROM brands WHERE name='Marca ficha CU01') WHERE id=?", id);
        jdbc.update("INSERT INTO product_images(product_id,position,url) VALUES (?,0,'https://example.com/lampara.jpg')", id);
        jdbc.update("INSERT INTO attributes(name) VALUES ('Potencia ficha CU01')");
        jdbc.update("INSERT INTO attribute_values(attribute_id,value_text) SELECT id,'8 W' FROM attributes WHERE name='Potencia ficha CU01'");
        jdbc.update("INSERT INTO product_attribute_values(product_id,attribute_value_id) SELECT ?,av.id FROM attribute_values av JOIN attributes a ON a.id=av.attribute_id WHERE a.name='Potencia ficha CU01'", id);
        jdbc.update("INSERT INTO product_variants(product_id,position,name,price,stock) VALUES (?,0,'Negra',55000,8)", id);
        perform(buyer, get("/api/products/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lampara ficha"))
                .andExpect(jsonPath("$.description").value("Para escritorio y lectura"))
                .andExpect(jsonPath("$.brand").value("Marca ficha CU01"))
                .andExpect(jsonPath("$.store.id").value(1))
                .andExpect(jsonPath("$.store.ownerAccountId").doesNotExist())
                .andExpect(jsonPath("$.imageUrls[0]").value("https://example.com/lampara.jpg"))
                .andExpect(jsonPath("$.specifications[0].value").value("8 W"))
                .andExpect(jsonPath("$.variants[0].name").value("Negra"));
    }

    @Test
    void missingOptionalInformationReturnsEmptyCollections() throws Exception {
        var buyer = sessionWithRole("ficha-vacia@example.com", "COMPRADOR");
        long id = seedProduct(1, "Producto sin ficha", 2, "10000");
        perform(buyer, get("/api/products/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrls").isEmpty())
                .andExpect(jsonPath("$.specifications").isEmpty())
                .andExpect(jsonPath("$.variants").isEmpty());
    }

    @Test
    void detailPreservesAuthenticationAndRetiredProductVisibility() throws Exception {
        long id = seedProduct(1, "Producto retirado ficha", 2, "10000");
        mvc.perform(get("/api/products/" + id)).andExpect(status().isUnauthorized());
        var buyer = sessionWithRole("ficha-restringida@example.com", "COMPRADOR");
        jdbc.update("UPDATE products SET active=FALSE,status='RETIRED' WHERE id=?", id);
        perform(buyer, get("/api/products/" + id)).andExpect(status().isNotFound());
    }
}
