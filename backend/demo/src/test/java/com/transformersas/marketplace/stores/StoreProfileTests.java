package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF-058 y A2: reglas del perfil público de la tienda. */
class StoreProfileTests {

    private void assertInvalid(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(BusinessException.Kind.INVALID);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test
    void nameIsStrippedAndInnerWhitespaceCollapsed() {
        assertThat(new StoreProfile("  Mi \t  Tienda\nCentro ", null).name()).isEqualTo("Mi Tienda Centro");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void nameIsRequired(String name) {
        assertInvalid(() -> new StoreProfile(name, "desc"), "STORE_NAME_REQUIRED");
    }

    @Test
    void nameAcceptsTheMaximumLengthAndRejectsOneMore() {
        assertThat(new StoreProfile("a".repeat(StoreProfile.NAME_MAX), null).name()).hasSize(StoreProfile.NAME_MAX);
        assertInvalid(() -> new StoreProfile("a".repeat(StoreProfile.NAME_MAX + 1), null), "STORE_NAME_TOO_LONG");
    }

    @Test
    void nameRejectsControlCharacters() {
        assertInvalid(() -> new StoreProfile("Tienda\u0000X", null), "STORE_NAME_INVALID");
    }

    @Test
    void descriptionIsOptionalAndBlankBecomesNull() {
        assertThat(new StoreProfile("Tienda", null).description()).isNull();
        assertThat(new StoreProfile("Tienda", "   \n ").description()).isNull();
    }

    @Test
    void descriptionKeepsLineBreaksAndIsStripped() {
        assertThat(new StoreProfile("Tienda", "  Línea 1\nLínea 2  ").description()).isEqualTo("Línea 1\nLínea 2");
    }

    @Test
    void descriptionAcceptsTheMaximumLengthAndRejectsOneMore() {
        assertThat(new StoreProfile("Tienda", "d".repeat(StoreProfile.DESCRIPTION_MAX)).description())
                .hasSize(StoreProfile.DESCRIPTION_MAX);
        assertInvalid(() -> new StoreProfile("Tienda", "d".repeat(StoreProfile.DESCRIPTION_MAX + 1)),
                "STORE_DESCRIPTION_TOO_LONG");
    }

    @Test
    void descriptionRejectsControlCharactersOtherThanLineBreaks() {
        assertInvalid(() -> new StoreProfile("Tienda", "hola\u0007"), "STORE_DESCRIPTION_INVALID");
    }
}
