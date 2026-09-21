package com.transformersas.marketplace.sellers;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-12: una persona habilita el rol de vendedor, con cuenta nueva o con la que ya tiene. */
class SellerRegistrationTests extends AbstractIntegrationTest {

    private static final String API = "/api/sellers";

    // ---------- Ayudas ----------

    /** POST sin sesión, como lo haría un visitante: solo lleva el token CSRF que entrega /api/auth/csrf. */
    private ResultActions publicPost(String path, String body) throws Exception {
        var csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode token = json.readTree(csrf.getResponse().getContentAsString());
        return mvc.perform(post(path).cookie(csrf.getResponse().getCookie("SESSION"))
                .header(token.get("headerName").asString(), token.get("token").asString())
                .contentType("application/json").content(body));
    }

    private static String registerBody(String email, String password, String storeName, boolean acceptTerms) {
        return """
                {"email":"%s","password":"%s","storeName":"%s","acceptTerms":%s}""".formatted(email, password, storeName,
                acceptTerms);
    }

    private static String enableBody(String storeName, boolean acceptTerms) {
        return "{\"storeName\":\"" + storeName + "\",\"acceptTerms\":" + acceptTerms + "}";
    }

    private ResultActions register(String email, String storeName) throws Exception {
        return publicPost(API + "/register", registerBody(email, PASSWORD, storeName, true));
    }

    private ResultActions enable(Session session, String storeName, boolean acceptTerms) throws Exception {
        return perform(session, post(API + "/enable").contentType("application/json")
                .content(enableBody(storeName, acceptTerms)));
    }

    private ResultActions verify(String email, String storeName) throws Exception {
        return publicPost(API + "/verify-email", "{\"email\":\"" + email + "\",\"storeName\":\"" + storeName + "\"}");
    }

    private List<String> rolesOf(String email) {
        return jdbc.queryForList("SELECT role FROM user_account_roles WHERE account_id = ?", String.class,
                accountIdOf(email));
    }

    private void markEmailVerified(String email) {
        jdbc.update("UPDATE user_accounts SET email_verified_at = NOW(6) WHERE email = ?", email);
    }

    // ---------- Condiciones ----------

    @Test
    void termsArePublicAndCarryTheirVersion() throws Exception {
        mvc.perform(get(API + "/terms")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(SellerTerms.VERSION))
                .andExpect(jsonPath("$.text", containsString("reclamaciones")));
    }

    // ---------- Sin cuenta: se registra una nueva ----------

    @Test
    void visitorRegistersAnAccountAndItsStoreThenConfirmsItToBecomeASeller() throws Exception {
        register("nuevo@example.com", "Mi Tienda Nueva").andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeName").value("Mi Tienda Nueva"))
                .andExpect(jsonPath("$.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.sellerRoleActive").value(false));

        long accountId = accountIdOf("nuevo@example.com");
        assertThat(jdbc.queryForObject("SELECT status FROM user_accounts WHERE id = ?", String.class, accountId))
                .isEqualTo("ACTIVA");
        assertThat(rolesOf("nuevo@example.com")).containsExactly("COMPRADOR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE owner_account_id = ? AND name = ?",
                Integer.class, accountId, "Mi Tienda Nueva")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT terms_version FROM seller_terms_acceptances WHERE account_id = ?",
                String.class, accountId)).isEqualTo(SellerTerms.VERSION);
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM user_accounts WHERE id = ?",
                Boolean.class, accountId)).isTrue();

        // Antes de confirmar solo es comprador.
        Session before = login("nuevo@example.com");
        perform(before, get("/api/auth/me")).andExpect(jsonPath("$.roles", containsInAnyOrder("COMPRADOR")));

        // El nombre se compara sin distinguir mayúsculas, tildes ni espacios sobrantes.
        verify("Nuevo@Example.com", "  MI   tienda nueva ").andExpect(status().isNoContent());

        assertThat(rolesOf("nuevo@example.com")).containsExactlyInAnyOrder("COMPRADOR", "VENDEDOR");
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM user_accounts WHERE id = ?",
                Boolean.class, accountId)).isTrue();

        // Con una sesión nueva la misma cuenta ya puede operar como vendedor de su tienda.
        Session after = login("nuevo@example.com");
        perform(after, get("/api/auth/me")).andExpect(jsonPath("$.roles", containsInAnyOrder("COMPRADOR", "VENDEDOR")));
        perform(after, put("/api/auth/active-role").contentType("application/json").content("{\"role\":\"VENDEDOR\"}"))
                .andExpect(status().isOk());
        long storeId = jdbc.queryForObject("SELECT id FROM stores WHERE owner_account_id = ?", Long.class, accountId);
        performAsSeller(after, storeId, get("/api/seller/products")).andExpect(status().isOk());
    }

    @Test
    void registrationIsRejectedWhenTheEmailAlreadyHasAnAccount() throws Exception {
        createAccount("existente@example.com", "COMPRADOR");

        register("existente@example.com", "Otra Tienda").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        // El correo se compara sin distinguir mayúsculas.
        register("EXISTENTE@example.com", "Otra Tienda").andExpect(status().isConflict());
        assertThat(count("stores")).isEqualTo(1);
    }

    @Test
    void aTakenStoreNameRollsBackTheWholeRegistrationIncludingTheAccount() throws Exception {
        register("uno@example.com", "Tienda Unica").andExpect(status().isCreated());

        // El nombre ya está tomado (sin distinguir mayúsculas, tildes ni espacios sobrantes): no queda ni la cuenta.
        register("dos@example.com", "  TIENDA   única ").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_NAME_TAKEN"));
        register("tres@example.com", "Tienda principal").andExpect(status().isConflict());

        assertThat(jdbc.queryForList("SELECT email FROM user_accounts", String.class)).containsExactly("uno@example.com");
        assertThat(count("stores")).isEqualTo(2);
    }

    @Test
    void invalidRegistrationsAreRejectedAndNothingIsSaved() throws Exception {
        publicPost(API + "/register", registerBody("a@example.com", "corta", "Tienda", true))
                .andExpect(status().isBadRequest());
        publicPost(API + "/register", registerBody("no-es-un-correo", PASSWORD, "Tienda", true))
                .andExpect(status().isBadRequest());
        publicPost(API + "/register", registerBody("a@example.com", PASSWORD, "   ", true))
                .andExpect(status().isBadRequest());
        publicPost(API + "/register", registerBody("a@example.com", PASSWORD, "x".repeat(101), true))
                .andExpect(status().isBadRequest());
        publicPost(API + "/register", registerBody("a@example.com", "p".repeat(73), "Tienda", true))
                .andExpect(status().isBadRequest());
        publicPost(API + "/register", registerBody("a@example.com", PASSWORD, "Tienda", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("condiciones")));
        // Sin el campo, tampoco se consideran aceptadas.
        publicPost(API + "/register", "{\"email\":\"a@example.com\",\"password\":\"" + PASSWORD
                + "\",\"storeName\":\"Tienda\"}").andExpect(status().isBadRequest());

        assertThat(count("user_accounts")).isZero();
        assertThat(count("stores")).isEqualTo(1);
    }

    // ---------- Confirmación del registro ----------

    @Test
    void confirmationIsRejectedWhenItDoesNotMatchTheRegistration() throws Exception {
        register("nuevo@example.com", "Mi Tienda").andExpect(status().isCreated());
        createAccount("sintienda@example.com", "COMPRADOR");

        // Nombre equivocado, correo desconocido y cuenta sin tienda dan la misma respuesta: no se revela quién tiene cuenta.
        verify("nuevo@example.com", "Otra Tienda").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));
        verify("nadie@example.com", "Mi Tienda").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));
        verify("sintienda@example.com", "Mi Tienda").andExpect(status().isBadRequest());
        // Campos vacíos o ausentes.
        verify("nuevo@example.com", " ").andExpect(status().isBadRequest());
        publicPost(API + "/verify-email", "{\"email\":\"nuevo@example.com\"}").andExpect(status().isBadRequest());

        assertThat(rolesOf("nuevo@example.com")).containsExactly("COMPRADOR");
    }

    @Test
    void confirmingTwiceChangesNothing() throws Exception {
        register("nuevo@example.com", "Mi Tienda").andExpect(status().isCreated());

        verify("nuevo@example.com", "Mi Tienda").andExpect(status().isNoContent());
        verify("nuevo@example.com", "Mi Tienda").andExpect(status().isNoContent());

        assertThat(rolesOf("nuevo@example.com")).containsExactlyInAnyOrder("COMPRADOR", "VENDEDOR");
    }

    // ---------- Con cuenta: se usa la misma cuenta y credenciales ----------

    @Test
    void anAccountWithAConfirmedRegistrationBecomesASellerRightAway() throws Exception {
        createAccount("comprador@example.com", "COMPRADOR");
        markEmailVerified("comprador@example.com");
        Session buyer = login("comprador@example.com");

        enable(buyer, "Tienda del Comprador", true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeName").value("Tienda del Comprador"))
                .andExpect(jsonPath("$.emailVerificationRequired").value(false))
                .andExpect(jsonPath("$.sellerRoleActive").value(true));

        long accountId = accountIdOf("comprador@example.com");
        assertThat(rolesOf("comprador@example.com")).containsExactlyInAnyOrder("COMPRADOR", "VENDEDOR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE owner_account_id = ?", Integer.class,
                accountId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT terms_version FROM seller_terms_acceptances WHERE account_id = ?",
                String.class, accountId)).isEqualTo(SellerTerms.VERSION);

        // La misma contraseña de siempre: con una sesión nueva ya figura el rol.
        perform(login("comprador@example.com"), get("/api/auth/me"))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("COMPRADOR", "VENDEDOR")));
    }

    @Test
    void anAccountWithoutConfirmationReservesItsStoreButWaitsToConfirm() throws Exception {
        createAccount("sinconfirmar@example.com", "COMPRADOR");
        Session buyer = login("sinconfirmar@example.com");

        enable(buyer, "Tienda Pendiente", true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.sellerRoleActive").value(false));

        assertThat(rolesOf("sinconfirmar@example.com")).containsExactly("COMPRADOR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE name = 'Tienda Pendiente'", Integer.class))
                .isEqualTo(1);

        verify("sinconfirmar@example.com", "Tienda Pendiente").andExpect(status().isNoContent());
        assertThat(rolesOf("sinconfirmar@example.com")).containsExactlyInAnyOrder("COMPRADOR", "VENDEDOR");
    }

    @Test
    void enablingIsRefusedWhenItDoesNotApply() throws Exception {
        createAccount("vendedor@example.com", "COMPRADOR", "VENDEDOR");
        createAccount("duena@example.com", "COMPRADOR");
        createAccount("otra@example.com", "COMPRADOR");
        markEmailVerified("vendedor@example.com");
        markEmailVerified("duena@example.com");
        markEmailVerified("otra@example.com");
        assignStoreOwner(1, accountIdOf("duena@example.com"));

        // Ya es vendedor.
        enable(login("vendedor@example.com"), "Otra Tienda", true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELLER_ALREADY_ENABLED"));
        // Ya es dueña de una tienda (una cuenta, una tienda).
        enable(login("duena@example.com"), "Segunda Tienda", true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_OWNER_ALREADY_HAS_STORE"));

        Session other = login("otra@example.com");
        // Nombre tomado, condiciones sin aceptar y nombre vacío.
        enable(other, "tienda PRINCIPAL", true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_NAME_TAKEN"));
        enable(other, "Tienda Libre", false).andExpect(status().isBadRequest());
        enable(other, " ", true).andExpect(status().isBadRequest());

        assertThat(rolesOf("otra@example.com")).containsExactly("COMPRADOR");
        assertThat(count("stores")).isEqualTo(1);
    }

    @Test
    void enablingNeedsASession() throws Exception {
        // Con el token CSRF correcto pero sin iniciar sesión.
        publicPost(API + "/enable", enableBody("Tienda", true)).andExpect(status().isUnauthorized());
    }

    @Test
    void theStoreOfANewSellerCannotBeOperatedUntilTheRegistrationIsConfirmed() throws Exception {
        register("nuevo@example.com", "Mi Tienda").andExpect(status().isCreated());
        Session buyerOnly = login("nuevo@example.com");
        long storeId = jdbc.queryForObject("SELECT id FROM stores WHERE name = 'Mi Tienda'", Long.class);

        // Sin el rol VENDEDOR la tienda existe pero no se puede operar.
        performAsSeller(buyerOnly, storeId, get("/api/seller/products")).andExpect(status().isForbidden());
        perform(buyerOnly, get("/api/auth/me")).andExpect(jsonPath("$.roles", not(hasItem("VENDEDOR"))));
    }
}
