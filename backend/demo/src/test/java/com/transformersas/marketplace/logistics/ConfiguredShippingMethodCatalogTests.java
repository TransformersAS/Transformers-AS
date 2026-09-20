package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.infrastructure.gateway.ConfiguredShippingMethodCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF-061: catálogo de métodos de envío que la tienda puede habilitar. */
class ConfiguredShippingMethodCatalogTests {

    @Test
    void keepsTheConfiguredMethodsInOrderWithoutBlanksOrRepeats() {
        var catalog = new ConfiguredShippingMethodCatalog(List.of(" STANDARD ", "EXPRESS", "", "STANDARD", "SAME_DAY"));

        assertThat(catalog.availableMethods()).containsExactly("STANDARD", "EXPRESS", "SAME_DAY");
    }

    @Test
    void anEmptyCatalogPreventsStartup() {
        assertThatThrownBy(() -> new ConfiguredShippingMethodCatalog(List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("al menos un método");
        assertThatThrownBy(() -> new ConfiguredShippingMethodCatalog(List.of("  ", "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidMethodNamesPreventStartup() {
        for (String invalid : new String[]{"standard", "EX PRESS", "1EXPRESS", "A".repeat(31), "EXPRESS!"}) {
            assertThatThrownBy(() -> new ConfiguredShippingMethodCatalog(List.of("STANDARD", invalid)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(invalid);
        }
    }
}
