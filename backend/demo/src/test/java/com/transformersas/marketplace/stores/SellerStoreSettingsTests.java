package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RF-058 a RF-060 y A1, A2, A3, A5, A8, A10: consulta, vista previa y guardado de la configuración de la tienda. */
class SellerStoreSettingsTests extends AbstractIntegrationTest {

    private Session seller;

    @BeforeEach
    void setUp() throws Exception {
        seedStore(2, "Otra tienda");
        seller = sellerOfStore("seller@example.com", 1);
    }

    private Map<String, Object> settings(Object version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Mi Tienda Editada");
        body.put("description", "Ropa y accesorios");
        body.put("contactEmail", "Ventas@MiTienda.co");
        body.put("contactPhone", "+57  300 123-4567");
        body.put("businessHours", "Lun-Vie 8-18");
        body.put("returnWindowDays", 45);
        body.put("policyText", "Devoluciones sin costo");
        body.put("shippingMethods", List.of("STANDARD", "EXPRESS"));
        body.put("version", version);
        return body;
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               Map<String, Object> body) throws Exception {
        return perform(seller, request.contentType("application/json").content(json.writeValueAsString(body)));
    }

    private ResultActions save(Map<String, Object> body) throws Exception {
        return send(put("/api/seller/store"), body);
    }

    private ResultActions preview(Map<String, Object> body) throws Exception {
        return send(post("/api/seller/store/preview"), body);
    }

    private void setStatus(String status, String reason) {
        jdbc.update("UPDATE stores SET status = ?, status_reason = ? WHERE id = 1", status, reason);
    }

    private String storedName() {
        return jdbc.queryForObject("SELECT name FROM stores WHERE id = 1", String.class);
    }

    // ---------- Consulta ----------

    @Test
    void rf058_theOwnerReadsTheStoreWithoutSendingTheHeader() throws Exception {
        perform(seller, get("/api/seller/store")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1)).andExpect(jsonPath("$.name").value("Tienda principal"))
                .andExpect(jsonPath("$.description").value(nullValue())).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.canModify").value(true)).andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.returnWindowDays").value(30))
                .andExpect(jsonPath("$.minReturnWindowDays").value(30))
                .andExpect(jsonPath("$.shippingMethods.enabled", contains("EXPRESS", "STANDARD")))
                .andExpect(jsonPath("$.shippingMethods.available", contains("STANDARD", "EXPRESS")));
    }

    @Test
    void a8_theReadIsAlwaysAllowedAndReportsTheReasonWhenRestrictedOrSuspended() throws Exception {
        setStatus("RESTRICTED", "Reclamaciones pendientes");
        perform(seller, get("/api/seller/store")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESTRICTED"))
                .andExpect(jsonPath("$.statusReason").value("Reclamaciones pendientes"))
                .andExpect(jsonPath("$.canModify").value(false));

        setStatus("SUSPENDED", "Incumplimiento de políticas");
        perform(seller, get("/api/seller/store")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.canModify").value(false));
    }

    @Test
    void rf062_aBuyerCannotReadOrEditTheStoreSettings() throws Exception {
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");

        perform(buyer, get("/api/seller/store")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_ROLE_REQUIRED"));
        perform(buyer, put("/api/seller/store").contentType("application/json")
                .content(json.writeValueAsString(settings(0)))).andExpect(status().isForbidden());
        assertThat(storedName()).isEqualTo("Tienda principal");
    }

    // ---------- Guardado ----------

    @Test
    void rf058_rf059_rf060_savingReplacesTheProfileContactHoursAndPolicyNormalized() throws Exception {
        save(settings(0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mi Tienda Editada"))
                .andExpect(jsonPath("$.description").value("Ropa y accesorios"))
                .andExpect(jsonPath("$.contactEmail").value("ventas@mitienda.co"))
                .andExpect(jsonPath("$.contactPhone").value("+57 300 123-4567"))
                .andExpect(jsonPath("$.businessHours").value("Lun-Vie 8-18"))
                .andExpect(jsonPath("$.returnWindowDays").value(45))
                .andExpect(jsonPath("$.policyText").value("Devoluciones sin costo"))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.canModify").value(true));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM stores WHERE id = 1");
        assertThat(row).containsEntry("name", "Mi Tienda Editada").containsEntry("return_window_days", 45)
                .containsEntry("contact_email", "ventas@mitienda.co").containsEntry("status", "ACTIVE");
        assertThat(row.get("owner_account_id")).isEqualTo(accountIdOf("seller@example.com"));
    }

    @Test
    void savingWithOptionalDataOmittedClearsItAndAppliesTheDefaultPolicy() throws Exception {
        save(settings(0)).andExpect(status().isOk());

        save(Map.of("name", "Solo nombre", "shippingMethods", List.of("STANDARD"), "version", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.contactEmail").value(nullValue()))
                .andExpect(jsonPath("$.businessHours").value(nullValue()))
                .andExpect(jsonPath("$.returnWindowDays").value(30))
                .andExpect(jsonPath("$.policyText").value(nullValue())).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void theAuditRecordsWhoChangedWhichFieldsWithoutTheirValues() throws Exception {
        save(settings(0)).andExpect(status().isOk());

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT * FROM audit_events WHERE action = 'STORE_SETTINGS_UPDATED'");
        assertThat(audit).containsEntry("actor_type", "SELLER").containsEntry("entity_type", "STORE")
                .containsEntry("entity_id", "1").containsEntry("outcome", "SUCCESS")
                .containsEntry("actor_id", accountIdOf("seller@example.com"));
        String details = (String) audit.get("details");
        assertThat(details).contains("name", "contactEmail", "returnWindowDays", "policyText")
                .doesNotContain("Mi Tienda Editada", "mitienda.co", "300 123");
    }

    @Test
    void a1_aNameTakenByAnotherStoreIsRejectedIgnoringCaseAndAccents() throws Exception {
        seedStore(3, "Café Ñandú");
        Map<String, Object> body = settings(0);

        body.put("name", "OTRA TIENDA");
        save(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE_NAME_TAKEN"));
        body.put("name", "cafe nandu");
        save(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE_NAME_TAKEN"));

        assertThat(storedName()).isEqualTo("Tienda principal");
        body.put("name", "TIENDA PRINCIPAL"); // el propio nombre, con otras mayúsculas, es válido
        save(body).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("TIENDA PRINCIPAL"));
    }

    @Test
    void a2_incompleteOrMalformedDataIsRejectedAndNothingChanges() throws Exception {
        Map<String, Object> noName = settings(0);
        noName.put("name", "   ");
        save(noName).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STORE_NAME_REQUIRED"));

        Map<String, Object> noVersion = settings(null);
        save(noVersion).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STORE_VERSION_REQUIRED"));

        Map<String, Object> unknown = settings(0);
        unknown.put("status", "ACTIVE");
        save(unknown).andExpect(status().isBadRequest());

        Map<String, Object> tooLong = settings(0);
        tooLong.put("description", "d".repeat(1001));
        save(tooLong).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_DESCRIPTION_TOO_LONG"));

        assertThat(storedName()).isEqualTo("Tienda principal");
        assertThat(jdbc.queryForObject("SELECT version FROM stores WHERE id = 1", Long.class)).isZero();
    }

    @Test
    void a3_contactIsValidatedOnlyWhenItComes() throws Exception {
        Map<String, Object> badEmail = settings(0);
        badEmail.put("contactEmail", "no-es-un-correo");
        save(badEmail).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_CONTACT_EMAIL_INVALID"));

        Map<String, Object> badPhone = settings(0);
        badPhone.put("contactPhone", "abc");
        save(badPhone).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_CONTACT_PHONE_INVALID"));

        Map<String, Object> blankContact = settings(0);
        blankContact.put("contactEmail", "  ");
        blankContact.put("contactPhone", "");
        save(blankContact).andExpect(status().isOk()).andExpect(jsonPath("$.contactEmail").value(nullValue()))
                .andExpect(jsonPath("$.contactPhone").value(nullValue()));
    }

    @Test
    void a5_aReturnWindowBelowTheMarketplaceMinimumIsRejectedWithTheMinimum() throws Exception {
        Map<String, Object> body = settings(0);
        body.put("returnWindowDays", 29);

        save(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_POLICY_BELOW_MINIMUM"))
                .andExpect(jsonPath("$.details.minReturnWindowDays").value(30));

        body.put("returnWindowDays", 30);
        save(body).andExpect(status().isOk()).andExpect(jsonPath("$.returnWindowDays").value(30));
        body.put("returnWindowDays", 0);
        save(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STORE_RETURN_WINDOW_INVALID"));
    }

    @Test
    void a8_aRestrictedOrSuspendedStoreCannotBeModifiedAndTheReasonIsReported() throws Exception {
        setStatus("RESTRICTED", "Reclamaciones pendientes");
        save(settings(0)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_MODIFICATION_BLOCKED"))
                .andExpect(jsonPath("$.details.status").value("RESTRICTED"))
                .andExpect(jsonPath("$.details.reason").value("Reclamaciones pendientes"));

        setStatus("SUSPENDED", "Incumplimiento de políticas");
        save(settings(0)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.status").value("SUSPENDED"));

        assertThat(storedName()).isEqualTo("Tienda principal");
        assertThat(count("audit_events")).isZero();
    }

    @Test
    void a10_aStaleVersionIsRejectedAndTheFirstEditRemains() throws Exception {
        save(settings(0)).andExpect(status().isOk());
        Map<String, Object> second = settings(0);
        second.put("name", "Edición atrasada");

        save(second).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE_CONCURRENT_UPDATE"));

        assertThat(storedName()).isEqualTo("Mi Tienda Editada");
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void a9_abandoningAnEditKeepsTheStoredDataUntouched() throws Exception {
        save(settings(0)).andExpect(status().isOk());
        String before = perform(seller, get("/api/seller/store")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        Map<String, Object> abandoned = settings(1);
        abandoned.put("name", "Cambio que se abandona");
        abandoned.put("returnWindowDays", 90);

        preview(abandoned).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Cambio que se abandona"));
        abandoned.put("name", "   ");
        save(abandoned).andExpect(status().isBadRequest());

        String after = perform(seller, get("/api/seller/store")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(after).isEqualTo(before);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void theSellerOnlyEditsHisOwnStoreEvenIfAnotherStoreExists() throws Exception {
        Session other = sellerOfStore("otro@example.com", 2);

        perform(other, put("/api/seller/store").contentType("application/json")
                .content(json.writeValueAsString(settings(0)))).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT name FROM stores WHERE id = 2", String.class)).isEqualTo("Mi Tienda Editada");
        assertThat(storedName()).isEqualTo("Tienda principal");
    }

    // ---------- Vista previa ----------

    @Test
    void thePreviewShowsTheNormalizedSettingsWithoutSavingOrAuditing() throws Exception {
        preview(settings(null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mi Tienda Editada"))
                .andExpect(jsonPath("$.minReturnWindowDays").value(30))
                .andExpect(jsonPath("$.contactEmail").value("ventas@mitienda.co"))
                .andExpect(jsonPath("$.contactPhone").value("+57 300 123-4567"))
                .andExpect(jsonPath("$.returnWindowDays").value(45)).andExpect(jsonPath("$.version").value(0));

        assertThat(storedName()).isEqualTo("Tienda principal");
        assertThat(count("audit_events")).isZero();
    }

    @Test
    void thePreviewAppliesTheSameRulesAsSaving() throws Exception {
        Map<String, Object> taken = settings(null);
        taken.put("name", "otra tienda");
        preview(taken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE_NAME_TAKEN"));

        Map<String, Object> below = settings(null);
        below.put("returnWindowDays", 10);
        preview(below).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_POLICY_BELOW_MINIMUM"));

        Map<String, Object> badPhone = settings(null);
        badPhone.put("contactPhone", "123");
        preview(badPhone).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_CONTACT_PHONE_INVALID"));
    }

    @Test
    void thePreviewIsAllowedWhenRestrictedButReportsThatSavingIsNot() throws Exception {
        setStatus("RESTRICTED", "Reclamaciones pendientes");

        preview(settings(null)).andExpect(status().isOk()).andExpect(jsonPath("$.canModify").value(false))
                .andExpect(jsonPath("$.statusReason").value("Reclamaciones pendientes"));
    }

    // ---------- RF-061 ----------

    private List<String> storedMethods(long storeId) {
        return jdbc.queryForList("SELECT method FROM store_shipping_methods WHERE store_id = ? ORDER BY method",
                String.class, storeId);
    }

    @Test
    void rf061_theSellerChoosesWhichAvailableMethodsHeOffersAndTheyAreNormalized() throws Exception {
        Map<String, Object> body = settings(0);
        body.put("shippingMethods", List.of(" express ", "EXPRESS"));

        save(body).andExpect(status().isOk()).andExpect(jsonPath("$.shippingMethods.enabled", contains("EXPRESS")))
                .andExpect(jsonPath("$.shippingMethods.available", contains("STANDARD", "EXPRESS")));

        assertThat(storedMethods(1)).containsExactly("EXPRESS");
        perform(seller, get("/api/seller/store")).andExpect(jsonPath("$.shippingMethods.enabled", contains("EXPRESS")));
    }

    @Test
    void a6_aMethodTheMarketplaceDoesNotOfferIsRejectedAndNothingChanges() throws Exception {
        Map<String, Object> body = settings(0);
        body.put("shippingMethods", List.of("STANDARD", "DRONE"));

        save(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_SHIPPING_METHOD_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.unavailable", contains("DRONE")))
                .andExpect(jsonPath("$.details.available", contains("STANDARD", "EXPRESS")));

        assertThat(storedName()).isEqualTo("Tienda principal");
        assertThat(storedMethods(1)).containsExactly("EXPRESS", "STANDARD");
    }

    @Test
    void a2_theStoreMustKeepAtLeastOneShippingMethod() throws Exception {
        Map<String, Object> empty = settings(0);
        empty.put("shippingMethods", List.of());
        save(empty).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_SHIPPING_METHODS_REQUIRED"));

        Map<String, Object> blank = settings(0);
        blank.put("shippingMethods", List.of("  "));
        save(blank).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_SHIPPING_METHODS_REQUIRED"));

        Map<String, Object> missing = settings(0);
        missing.remove("shippingMethods");
        save(missing).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_SHIPPING_METHODS_REQUIRED"));

        assertThat(storedMethods(1)).containsExactly("EXPRESS", "STANDARD");
    }

    @Test
    void thePreviewShowsTheChosenMethodsWithoutSavingThemAndRejectsUnavailableOnes() throws Exception {
        Map<String, Object> body = settings(null);
        body.put("shippingMethods", List.of("EXPRESS"));
        preview(body).andExpect(status().isOk()).andExpect(jsonPath("$.shippingMethods.enabled", contains("EXPRESS")));
        assertThat(storedMethods(1)).containsExactly("EXPRESS", "STANDARD");

        body.put("shippingMethods", List.of("DRONE"));
        preview(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STORE_SHIPPING_METHOD_UNAVAILABLE"));
    }

    @Test
    void theAuditListsShippingMethodsAsChangedOnlyWhenTheSetReallyChanged() throws Exception {
        save(settings(0)).andExpect(status().isOk());
        Map<String, Object> onlyExpress = settings(1);
        onlyExpress.put("shippingMethods", List.of("EXPRESS"));
        save(onlyExpress).andExpect(status().isOk());

        List<String> details = jdbc.queryForList(
                "SELECT details FROM audit_events WHERE action = 'STORE_SETTINGS_UPDATED' ORDER BY id", String.class);
        assertThat(details).hasSize(2);
        assertThat(details.get(0)).doesNotContain("shippingMethods");
        assertThat(details.get(1)).contains("shippingMethods");
    }

    @Test
    void a8_aRestrictedStoreCannotChangeItsShippingMethods() throws Exception {
        setStatus("RESTRICTED", "Reclamaciones pendientes");
        Map<String, Object> body = settings(0);
        body.put("shippingMethods", List.of("EXPRESS"));

        save(body).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STORE_MODIFICATION_BLOCKED"));

        assertThat(storedMethods(1)).containsExactly("EXPRESS", "STANDARD");
    }
}
