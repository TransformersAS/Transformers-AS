package com.transformersas.marketplace.product;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductContentSnapshotProviderTests {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"not-a-product", "99999999999999999999999999"})
    void invalidProductIdentifierHasNoSnapshotAndNeverQueriesDatabase(String id) {
        var repository = mock(ProductRepository.class);
        assertThat(new ProductContentSnapshotProvider(repository).load(id)).isEmpty();
        verifyNoInteractions(repository);
    }
}
