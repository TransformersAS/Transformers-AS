package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.StorePolicy;
import com.transformersas.marketplace.stores.infrastructure.config.StorePolicyProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF-060: forma de la política y regla mínima configurable del marketplace (D5). */
class StorePolicyTests {

    private void assertInvalid(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.kind()).isEqualTo(BusinessException.Kind.INVALID);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    @Test
    void theDefaultPolicyOffersThirtyDaysWithoutText() {
        assertThat(StorePolicy.DEFAULT).isEqualTo(new StorePolicy(30, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -5, 0, 366, Integer.MAX_VALUE})
    void theReturnWindowMustBeBetweenOneAndTheMaximum(int days) {
        assertInvalid(() -> new StorePolicy(days, null), "STORE_RETURN_WINDOW_INVALID");
    }

    @Test
    void theReturnWindowAcceptsItsBounds() {
        assertThat(new StorePolicy(1, null).returnWindowDays()).isEqualTo(1);
        assertThat(new StorePolicy(StorePolicy.MAX_RETURN_WINDOW_DAYS, null).returnWindowDays()).isEqualTo(365);
    }

    @Test
    void theTextIsOptionalStrippedAndBounded() {
        assertThat(new StorePolicy(30, "   ").text()).isNull();
        assertThat(new StorePolicy(30, null).text()).isNull();
        assertThat(new StorePolicy(30, "  Sin cambios\nen ofertas ").text()).isEqualTo("Sin cambios\nen ofertas");
        assertThat(new StorePolicy(30, "p".repeat(StorePolicy.TEXT_MAX)).text()).hasSize(StorePolicy.TEXT_MAX);
        assertInvalid(() -> new StorePolicy(30, "p".repeat(StorePolicy.TEXT_MAX + 1)), "STORE_POLICY_TEXT_TOO_LONG");
        assertInvalid(() -> new StorePolicy(30, "hola\u0000"), "STORE_POLICY_TEXT_INVALID");
    }

    @Test
    void theMarketplaceMinimumDefaultsToThirtyAndCannotBeConfiguredBelowThat() {
        assertThat(new StorePolicyProperties(30).minReturnWindowDays()).isEqualTo(30);
        assertThat(new StorePolicyProperties(45).minReturnWindowDays()).isEqualTo(45);
        assertThatThrownBy(() -> new StorePolicyProperties(29)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("al menos 30");
    }
}
