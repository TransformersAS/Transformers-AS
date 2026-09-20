package com.transformersas.marketplace.catalog;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-06: el administrador configura categorías, marcas y atributos. */
class CatalogAdminTests extends AbstractIntegrationTest {

    private static final String CATEGORIES = "/api/admin/categories";
    private static final String BRANDS = "/api/admin/brands";
    private static final String ATTRIBUTES = "/api/admin/attributes";

    private Session admin;

    @BeforeEach
    void setUp() throws Exception {
        // La tabla categories apunta a sí misma: se quita el padre antes de borrar.
        jdbc.update("UPDATE categories SET parent_id = NULL");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM brands");
        jdbc.update("DELETE FROM attribute_values");
        jdbc.update("DELETE FROM attributes");
        admin = sessionWithRole("admin@example.com", "ADMIN");
    }

    // ---------- Ayudas ----------

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return perform(admin, request.contentType("application/json").content(body));
    }

    private static String category(String name, Long parentId) {
        return "{\"name\":\"" + name + "\",\"parentId\":" + parentId + "}";
    }

    private long createCategory(String name, Long parentId) throws Exception {
        String response = send(post(CATEGORIES), category(name, parentId)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private long createBrand(String name) throws Exception {
        String response = send(post(BRANDS), "{\"name\":\"" + name + "\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private long createAttribute(String name) throws Exception {
        String response = send(post(ATTRIBUTES), "{\"name\":\"" + name + "\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    // ---------- Seguridad ----------

    @Test
    void onlyAnAdminCanUseTheCatalogAdministration() throws Exception {
        mvc.perform(get(CATEGORIES)).andExpect(status().isUnauthorized());
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        perform(buyer, get(CATEGORIES)).andExpect(status().isForbidden());
        perform(buyer, get(BRANDS)).andExpect(status().isForbidden());
        perform(buyer, get(ATTRIBUTES)).andExpect(status().isForbidden());
    }

    // ---------- Categorías ----------

    @Test
    void treeShowsMainCategoriesWithTheirSubcategories() throws Exception {
        long technology = createCategory("Tecnología", null);
        createCategory("Celulares", technology);
        createCategory("Moda", null);

        perform(admin, get(CATEGORIES)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Moda"))
                .andExpect(jsonPath("$[1].name").value("Tecnología"))
                .andExpect(jsonPath("$[1].children[0].name").value("Celulares"))
                .andExpect(jsonPath("$[1].children[0].children", hasSize(0)));
    }

    @Test
    void siblingsCannotShareANameButDifferentParentsCan() throws Exception {
        long technology = createCategory("Tecnología", null);
        long home = createCategory("Hogar", null);
        createCategory("Accesorios", technology);

        send(post(CATEGORIES), category("TECNOLOGÍA", null)).andExpect(status().isConflict());
        send(post(CATEGORIES), category("accesorios", technology)).andExpect(status().isConflict());
        send(post(CATEGORIES), category("Accesorios", home)).andExpect(status().isCreated());
    }

    @Test
    void invalidCategoryRequestsAreRejected() throws Exception {
        send(post(CATEGORIES), category("   ", null)).andExpect(status().isBadRequest());
        send(post(CATEGORIES), category("Sin padre", 999_999L)).andExpect(status().isNotFound());

        long inactive = createCategory("Inactiva", null);
        perform(admin, post(CATEGORIES + "/" + inactive + "/deactivate")).andExpect(status().isOk());
        send(post(CATEGORIES), category("Hija", inactive)).andExpect(status().isConflict());
    }

    @Test
    void categoryCanBeRenamedAndMovedToAnotherParent() throws Exception {
        long technology = createCategory("Tecnología", null);
        long home = createCategory("Hogar", null);
        long lamps = createCategory("Lámparas", technology);

        send(put(CATEGORIES + "/" + lamps), category("Iluminación", home)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Iluminación"))
                .andExpect(jsonPath("$.parentId").value(home));
        // Guardar el mismo nombre no cuenta como repetido.
        send(put(CATEGORIES + "/" + lamps), category("Iluminación", home)).andExpect(status().isOk());

        perform(admin, get(CATEGORIES)).andExpect(jsonPath("$[0].name").value("Hogar"))
                .andExpect(jsonPath("$[0].children[0].name").value("Iluminación"))
                .andExpect(jsonPath("$[1].children", hasSize(0)));
    }

    @Test
    void moveCannotCreateACycleOrUseAMissingOrRepeatedParent() throws Exception {
        long a = createCategory("A", null);
        long b = createCategory("B", a);
        long c = createCategory("C", b);
        long other = createCategory("Otra", null);
        createCategory("B", other);

        send(put(CATEGORIES + "/" + a), category("A", a)).andExpect(status().isConflict());
        send(put(CATEGORIES + "/" + a), category("A", c)).andExpect(status().isConflict());
        send(put(CATEGORIES + "/" + a), category("A", 999_999L)).andExpect(status().isNotFound());
        send(put(CATEGORIES + "/" + b), category("B", other)).andExpect(status().isConflict());
        send(put(CATEGORIES + "/999999"), category("X", null)).andExpect(status().isNotFound());
    }

    @Test
    void categoryWithProductsCannotBeRenamedDeactivatedOrDeletedButCanBeMoved() throws Exception {
        long home = createCategory("Hogar", null);
        long parent = createCategory("Casa", null);
        seedProduct(1, "Lámpara", 5, "10.00"); // su categoría es "Hogar"

        send(put(CATEGORIES + "/" + home), category("Hogar y jardín", null)).andExpect(status().isConflict());
        perform(admin, post(CATEGORIES + "/" + home + "/deactivate")).andExpect(status().isConflict());
        perform(admin, delete(CATEGORIES + "/" + home)).andExpect(status().isConflict());
        send(put(CATEGORIES + "/" + home), category("Hogar", parent)).andExpect(status().isOk());
    }

    @Test
    void deactivateNeedsInactiveSubcategoriesAndActivateNeedsAnActiveParent() throws Exception {
        long technology = createCategory("Tecnología", null);
        long phones = createCategory("Celulares", technology);

        perform(admin, post(CATEGORIES + "/" + technology + "/deactivate")).andExpect(status().isConflict());
        perform(admin, post(CATEGORIES + "/" + phones + "/deactivate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        perform(admin, post(CATEGORIES + "/" + technology + "/deactivate")).andExpect(status().isOk());

        perform(admin, post(CATEGORIES + "/" + phones + "/activate")).andExpect(status().isConflict());
        perform(admin, post(CATEGORIES + "/" + technology + "/activate")).andExpect(status().isOk());
        perform(admin, post(CATEGORIES + "/" + phones + "/activate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        perform(admin, post(CATEGORIES + "/999999/activate")).andExpect(status().isNotFound());
    }

    @Test
    void categoryCanOnlyBeDeletedWithoutSubcategoriesOrProducts() throws Exception {
        long technology = createCategory("Tecnología", null);
        long phones = createCategory("Celulares", technology);

        perform(admin, delete(CATEGORIES + "/" + technology)).andExpect(status().isConflict());
        perform(admin, delete(CATEGORIES + "/" + phones)).andExpect(status().isNoContent());
        perform(admin, delete(CATEGORIES + "/" + phones)).andExpect(status().isNotFound());
        perform(admin, delete(CATEGORIES + "/" + technology)).andExpect(status().isNoContent());
        perform(admin, get(CATEGORIES)).andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- Marcas ----------

    @Test
    void brandsCanBeCreatedRenamedDeactivatedAndDeleted() throws Exception {
        long nike = createBrand("Nike");
        createBrand("Adidas");

        perform(admin, get(BRANDS)).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Adidas"));
        send(put(BRANDS + "/" + nike), "{\"name\":\"Nike Sport\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nike Sport"));
        send(put(BRANDS + "/" + nike), "{\"name\":\"Nike Sport\"}").andExpect(status().isOk());
        perform(admin, post(BRANDS + "/" + nike + "/deactivate")).andExpect(jsonPath("$.active").value(false));
        perform(admin, post(BRANDS + "/" + nike + "/activate")).andExpect(jsonPath("$.active").value(true));
        perform(admin, delete(BRANDS + "/" + nike)).andExpect(status().isNoContent());
        perform(admin, get(BRANDS)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void invalidBrandRequestsAreRejected() throws Exception {
        long nike = createBrand("Nike");
        long adidas = createBrand("Adidas");

        send(post(BRANDS), "{\"name\":\"nike\"}").andExpect(status().isConflict());
        send(put(BRANDS + "/" + adidas), "{\"name\":\"NIKE\"}").andExpect(status().isConflict());
        send(post(BRANDS), "{\"name\":\" \"}").andExpect(status().isBadRequest());
        send(put(BRANDS + "/999999"), "{\"name\":\"X\"}").andExpect(status().isNotFound());
        perform(admin, delete(BRANDS + "/999999")).andExpect(status().isNotFound());
        perform(admin, get(BRANDS)).andExpect(jsonPath("$", hasSize(2)));
        perform(admin, post(BRANDS + "/" + nike + "/deactivate")).andExpect(status().isOk());
    }

    // ---------- Atributos ----------

    @Test
    void attributeHoldsItsAllowedValues() throws Exception {
        long color = createAttribute("Color");
        createAttribute("Talla");

        send(post(ATTRIBUTES + "/" + color + "/values"), "{\"value\":\"Rojo\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.values", hasSize(1)));
        String withTwo = send(post(ATTRIBUTES + "/" + color + "/values"), "{\"value\":\"Azul\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.values", hasSize(2)))
                .andReturn().getResponse().getContentAsString();

        perform(admin, get(ATTRIBUTES)).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Color"))
                .andExpect(jsonPath("$[0].values", hasSize(2)));

        long redId = json.readTree(withTwo).get("values").get(0).get("id").asLong();
        perform(admin, delete(ATTRIBUTES + "/" + color + "/values/" + redId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.values", hasSize(1)));
        perform(admin, delete(ATTRIBUTES + "/" + color + "/values/" + redId)).andExpect(status().isNotFound());
    }

    @Test
    void attributesCanBeRenamedAndDeletedWithTheirValues() throws Exception {
        long color = createAttribute("Color");
        send(post(ATTRIBUTES + "/" + color + "/values"), "{\"value\":\"Rojo\"}").andExpect(status().isCreated());

        send(put(ATTRIBUTES + "/" + color), "{\"name\":\"Colores\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Colores"));
        send(put(ATTRIBUTES + "/" + color), "{\"name\":\"Colores\"}").andExpect(status().isOk());
        perform(admin, delete(ATTRIBUTES + "/" + color)).andExpect(status().isNoContent());

        perform(admin, get(ATTRIBUTES)).andExpect(jsonPath("$", hasSize(0)));
        assertThat(count("attribute_values")).isZero();
    }

    @Test
    void invalidAttributeRequestsAreRejected() throws Exception {
        long color = createAttribute("Color");
        long size = createAttribute("Talla");
        send(post(ATTRIBUTES + "/" + color + "/values"), "{\"value\":\"Rojo\"}").andExpect(status().isCreated());

        send(post(ATTRIBUTES), "{\"name\":\"COLOR\"}").andExpect(status().isConflict());
        send(put(ATTRIBUTES + "/" + size), "{\"name\":\"color\"}").andExpect(status().isConflict());
        send(post(ATTRIBUTES), "{\"name\":\"\"}").andExpect(status().isBadRequest());
        send(post(ATTRIBUTES + "/" + color + "/values"), "{\"value\":\"rojo\"}").andExpect(status().isConflict());
        send(post(ATTRIBUTES + "/" + color + "/values"), "{\"value\":\" \"}").andExpect(status().isBadRequest());
        send(post(ATTRIBUTES + "/999999/values"), "{\"value\":\"X\"}").andExpect(status().isNotFound());
        send(put(ATTRIBUTES + "/999999"), "{\"name\":\"X\"}").andExpect(status().isNotFound());
        perform(admin, delete(ATTRIBUTES + "/999999")).andExpect(status().isNotFound());
    }
}
