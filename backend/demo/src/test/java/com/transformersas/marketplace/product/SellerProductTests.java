package com.transformersas.marketplace.product;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-14: el vendedor publica y mantiene los productos de su tienda. */
class SellerProductTests extends AbstractIntegrationTest {

    private static final String API = "/api/seller/products";

    private Session seller;
    private long brandId;
    private long redId;
    private long blueId;

    @BeforeEach
    void setUp() throws Exception {
        cleanCatalog();
        // Desde CU-18 el vendedor debe ser el dueño de la tienda cuyo X-Store-Id envía.
        seller = sellerOfStore("seller@example.com", 1);
        seedStore(2, "Otra tienda");
        jdbc.update("INSERT INTO categories (name) VALUES ('Ropa')");
        jdbc.update("INSERT INTO brands (name) VALUES ('Nike')");
        brandId = jdbc.queryForObject("SELECT id FROM brands WHERE name = 'Nike'", Long.class);
        jdbc.update("INSERT INTO attributes (name) VALUES ('Color')");
        long colorId = jdbc.queryForObject("SELECT id FROM attributes", Long.class);
        jdbc.update("INSERT INTO attribute_values (attribute_id, value_text) VALUES (?, 'Rojo'), (?, 'Azul')",
                colorId, colorId);
        redId = jdbc.queryForObject("SELECT id FROM attribute_values WHERE value_text = 'Rojo'", Long.class);
        blueId = jdbc.queryForObject("SELECT id FROM attribute_values WHERE value_text = 'Azul'", Long.class);
    }

    /** Deja la base sin datos de este caso de uso para no estorbar a las demás clases de prueba. */
    @AfterEach
    void tearDown() {
        cleanCatalog();
    }

    private void cleanCatalog() {
        jdbc.update("DELETE FROM product_variants");
        jdbc.update("DELETE FROM product_attribute_values");
        jdbc.update("DELETE FROM product_images");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM attribute_values");
        jdbc.update("DELETE FROM attributes");
        jdbc.update("DELETE FROM brands");
        jdbc.update("UPDATE categories SET parent_id = NULL");
        jdbc.update("DELETE FROM categories");
    }

    // ---------- Ayudas ----------

    private ResultActions api(MockHttpServletRequestBuilder request) throws Exception {
        return performAsSeller(seller, 1, request);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return api(request.contentType("application/json").content(body));
    }

    private String body(String name, String category, String images) {
        return """
                {"name":"%s","description":"Buena calidad","price":25000,"stock":10,"category":"%s",
                 "brandId":%d,"imageUrls":%s,"attributeValueIds":[%d],
                 "variants":[{"name":"Talla M","price":26000,"stock":4}]}"""
                .formatted(name, category, brandId, images, redId);
    }

    private long createDraft(String name) throws Exception {
        String response = send(post(API), body(name, "Ropa", "[\"https://img.example/a.jpg\"]"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private long createActive(String name) throws Exception {
        long id = createDraft(name);
        api(post(API + "/" + id + "/publish")).andExpect(status().isOk());
        return id;
    }

    // ---------- Crear y ver la vista previa ----------

    @Test
    void newProductStartsAsADraftInTheSellersStoreWithAllItsData() throws Exception {
        long id = createDraft("Camiseta");

        api(get(API + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.name").value("Camiseta"))
                .andExpect(jsonPath("$.category").value("Ropa"))
                .andExpect(jsonPath("$.brandId").value(brandId))
                .andExpect(jsonPath("$.imageUrls", contains("https://img.example/a.jpg")))
                .andExpect(jsonPath("$.attributeValueIds", contains((int) redId)))
                .andExpect(jsonPath("$.variants[0].name").value("Talla M"))
                .andExpect(jsonPath("$.variants[0].stock").value(4));
        assertThat(jdbc.queryForObject("SELECT store_id FROM products WHERE id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, id)).isFalse();
    }

    @Test
    void optionalPartsCanBeOmitted() throws Exception {
        send(post(API), """
                {"name":"Simple","price":10,"stock":1,"category":"Ropa"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brandId").value(nullValue()))
                .andExpect(jsonPath("$.imageUrls", empty()))
                .andExpect(jsonPath("$.variants", empty()));
    }

    @Test
    void invalidDataIsRejectedAndNothingIsSaved() throws Exception {
        String base = body("X", "Ropa", "[\"u\"]");
        send(post(API), body(" ", "Ropa", "[]")).andExpect(status().isBadRequest());
        send(post(API), base.replace("25000", "-1")).andExpect(status().isBadRequest());
        send(post(API), base.replace("\"stock\":10", "\"stock\":-5")).andExpect(status().isBadRequest());
        send(post(API), base.replace("\"Talla M\"", "\" \"")).andExpect(status().isBadRequest());
        send(post(API), body("X", "NoExiste", "[]")).andExpect(status().isBadRequest());
        send(post(API), base.replace("\"brandId\":" + brandId, "\"brandId\":999999")).andExpect(status().isBadRequest());
        send(post(API), base.replace("[" + redId + "]", "[999999]")).andExpect(status().isBadRequest());
        assertThat(count("products")).isZero();
    }

    @Test
    void inactiveCategoryOrBrandCannotBeUsed() throws Exception {
        jdbc.update("UPDATE categories SET active = FALSE");
        send(post(API), body("X", "Ropa", "[]")).andExpect(status().isBadRequest());
        jdbc.update("UPDATE categories SET active = TRUE");
        jdbc.update("UPDATE brands SET active = FALSE");
        send(post(API), body("X", "Ropa", "[]")).andExpect(status().isBadRequest());
    }

    // ---------- Publicar ----------

    @Test
    void publishNeedsAnImageAndOnlyWorksOnDrafts() throws Exception {
        long withoutImage = json.readTree(send(post(API), body("Sin foto", "Ropa", "[]"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        api(post(API + "/" + withoutImage + "/publish")).andExpect(status().isConflict());

        long id = createDraft("Camiseta");
        api(post(API + "/" + id + "/publish")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, id)).isTrue();
        api(post(API + "/" + id + "/publish")).andExpect(status().isConflict());
    }

    @Test
    void publishFailsIfTheCategoryWasDeactivatedMeanwhile() throws Exception {
        long id = createDraft("Camiseta");
        jdbc.update("UPDATE categories SET active = FALSE");
        api(post(API + "/" + id + "/publish")).andExpect(status().isBadRequest());
    }

    // ---------- Editar, pausar, reactivar, retirar ----------

    @Test
    void productCanBeEditedAndItsPartsAreReplaced() throws Exception {
        long id = createActive("Camiseta");

        send(put(API + "/" + id), body("Camiseta nueva", "Ropa", "[\"https://img.example/b.jpg\",\"https://img.example/c.jpg\"]")
                .replace("[" + redId + "]", "[" + blueId + "]").replace("Talla M", "Talla L"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Camiseta nueva"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.imageUrls", contains("https://img.example/b.jpg", "https://img.example/c.jpg")))
                .andExpect(jsonPath("$.attributeValueIds", contains((int) blueId)))
                .andExpect(jsonPath("$.variants", hasSize(1)))
                .andExpect(jsonPath("$.variants[0].name").value("Talla L"));
        assertThat(count("product_images")).isEqualTo(2);
    }

    @Test
    void pauseAndReactivateFollowTheStateRules() throws Exception {
        long id = createActive("Camiseta");

        api(post(API + "/" + id + "/reactivate")).andExpect(status().isConflict());
        api(post(API + "/" + id + "/pause")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, id)).isFalse();
        api(post(API + "/" + id + "/pause")).andExpect(status().isConflict());

        api(post(API + "/" + id + "/reactivate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        jdbc.update("UPDATE products SET status = 'PAUSED', active = FALSE");
        jdbc.update("UPDATE categories SET active = FALSE");
        api(post(API + "/" + id + "/reactivate")).andExpect(status().isBadRequest());
    }

    @Test
    void retiredProductCannotBeEditedPausedReactivatedOrRetiredAgain() throws Exception {
        long id = createActive("Camiseta");

        api(post(API + "/" + id + "/retire")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
        api(post(API + "/" + id + "/retire")).andExpect(status().isConflict());
        api(post(API + "/" + id + "/pause")).andExpect(status().isConflict());
        api(post(API + "/" + id + "/reactivate")).andExpect(status().isConflict());
        send(put(API + "/" + id), body("Otra", "Ropa", "[]")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, id)).isFalse();
    }

    // ---------- Duplicar ----------

    @Test
    void duplicateCreatesADraftCopyWithTheSameData() throws Exception {
        long id = createActive("Camiseta");

        String response = api(post(API + "/" + id + "/duplicate")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Copia de Camiseta"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.imageUrls", contains("https://img.example/a.jpg")))
                .andExpect(jsonPath("$.attributeValueIds", contains((int) redId)))
                .andExpect(jsonPath("$.variants[0].name").value("Talla M"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).get("id").asLong()).isNotEqualTo(id);
        assertThat(count("products")).isEqualTo(2);
    }

    @Test
    void duplicatedNameIsCutToTheColumnLimit() throws Exception {
        long id = createDraft("Camiseta");
        jdbc.update("UPDATE products SET name = ? WHERE id = ?", "N".repeat(255), id);

        api(post(API + "/" + id + "/duplicate")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(("Copia de " + "N".repeat(255)).substring(0, 255)));
    }

    // ---------- Buscar ----------

    @Test
    void searchFiltersByTextAndStatusAndListsNewestFirst() throws Exception {
        long shirt = createActive("Camiseta roja");
        long jacket = createDraft("Chaqueta");
        long pants = createActive("Pantalón");
        api(post(API + "/" + pants + "/pause")).andExpect(status().isOk());

        api(get(API)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].id", contains((int) pants, (int) jacket, (int) shirt)));
        api(get(API + "?q=CAMISETA")).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(shirt));
        api(get(API + "?q=ropa")).andExpect(jsonPath("$", hasSize(3)));
        api(get(API + "?status=DRAFT")).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(jacket));
        api(get(API + "?status=ACTIVE&q=pant")).andExpect(jsonPath("$", empty()));
        api(get(API + "?status=NOPE")).andExpect(status().isBadRequest());
    }

    // ---------- Separación entre tiendas y roles ----------

    @Test
    void aSellerNeverSeesOrChangesAnotherStoresProducts() throws Exception {
        long mine = createActive("Mía");
        jdbc.update("INSERT INTO products (name, price, stock, category, active, store_id) "
                + "VALUES ('Ajena', 5, 1, 'Ropa', TRUE, 2)");
        long other = jdbc.queryForObject("SELECT id FROM products WHERE store_id = 2", Long.class);

        api(get(API)).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(mine));
        api(get(API + "/" + other)).andExpect(status().isNotFound());
        send(put(API + "/" + other), body("X", "Ropa", "[]")).andExpect(status().isNotFound());
        api(post(API + "/" + other + "/pause")).andExpect(status().isNotFound());
        api(post(API + "/" + other + "/retire")).andExpect(status().isNotFound());
        api(post(API + "/" + other + "/duplicate")).andExpect(status().isNotFound());
    }

    @Test
    void onlyASellerSessionCanUseTheSellerApi() throws Exception {
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        performAsSeller(buyer, 1, get(API)).andExpect(status().isForbidden());
    }

    // ---------- Lo que ven los compradores ----------

    @Test
    void buyersDoNotSeeDraftsOrRetiredProductsButPausedOnesKeepTheirPreviousBehaviour() throws Exception {
        Session buyer = sessionWithRole("buyer2@example.com", "COMPRADOR");
        long active = createActive("Activa");
        long draft = createDraft("Borrador");
        long retired = createActive("Retirada");
        api(post(API + "/" + retired + "/retire")).andExpect(status().isOk());
        long paused = createActive("Pausada");
        api(post(API + "/" + paused + "/pause")).andExpect(status().isOk());

        perform(buyer, get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder((int) active, (int) paused)));
        perform(buyer, get("/api/products/" + active)).andExpect(status().isOk());
        perform(buyer, get("/api/products/" + draft)).andExpect(status().isNotFound());
        perform(buyer, get("/api/products/" + retired)).andExpect(status().isNotFound());
    }

    @Test
    void productsCreatedWithTheOldEndpointKeepTheirStateInSync() throws Exception {
        String product = """
                {"name":"Vieja","price":10,"stock":1,"category":"Ropa","active":%s}""";
        perform(seller, post("/api/products").contentType("application/json").content(product.formatted("true")))
                .andExpect(status().isCreated());
        perform(seller, post("/api/products").contentType("application/json").content(product.formatted("false")))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForList("SELECT status FROM products WHERE active = TRUE", String.class))
                .containsExactly("ACTIVE");
        assertThat(jdbc.queryForList("SELECT status FROM products WHERE active = FALSE", String.class))
                .containsExactly("PAUSED");
    }

    // ---------- Marcas y atributos que ya usan productos (reglas de CU-17) ----------

    @Test
    void brandsAndAttributeValuesInUseCannotBeRemoved() throws Exception {
        Session admin = sessionWithRole("admin@example.com", "ADMIN");
        createDraft("Camiseta");
        long colorId = jdbc.queryForObject("SELECT id FROM attributes", Long.class);

        perform(admin, post("/api/admin/brands/" + brandId + "/deactivate")).andExpect(status().isConflict());
        perform(admin, delete("/api/admin/brands/" + brandId)).andExpect(status().isConflict());
        perform(admin, delete("/api/admin/attributes/" + colorId + "/values/" + redId))
                .andExpect(status().isConflict());
        perform(admin, delete("/api/admin/attributes/" + colorId)).andExpect(status().isConflict());

        // Un valor que ningún producto usa sí se puede quitar.
        perform(admin, delete("/api/admin/attributes/" + colorId + "/values/" + blueId))
                .andExpect(status().isOk());
    }
}
