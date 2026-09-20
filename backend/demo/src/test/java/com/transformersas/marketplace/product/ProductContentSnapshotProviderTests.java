package com.transformersas.marketplace.product;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductContentSnapshotProviderTests {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"not-a-product", "99999999999999999999999999"})
    void invalidProductIdentifierHasNoSnapshotAndNeverQueriesDatabase(String id) {
        var repository = mock(ProductRepository.class);
        var owners = mock(ProductContentOwnerResolver.class);
        assertThat(new ProductContentSnapshotProvider(repository, owners).load(id)).isEmpty();
        verifyNoInteractions(repository, owners);
    }

    @Test
    void theSnapshotCarriesTheOwnerOfTheProductsStore() {
        var repository = mock(ProductRepository.class);
        var owners = mock(ProductContentOwnerResolver.class);
        when(repository.findById(7L)).thenReturn(Optional.of(product()));
        when(owners.ownerAccountId("7")).thenReturn(Optional.of("42"));

        var snapshot = new ProductContentSnapshotProvider(repository, owners).load("7").orElseThrow();

        assertThat(snapshot.ownerId()).isEqualTo("42");
        assertThat(snapshot.title()).isEqualTo("Lámpara");
    }

    @Test
    void aProductOfAStoreWithoutAnOwnerHasNoOwnerInItsSnapshot() {
        var repository = mock(ProductRepository.class);
        var owners = mock(ProductContentOwnerResolver.class);
        when(repository.findById(7L)).thenReturn(Optional.of(product()));
        when(owners.ownerAccountId("7")).thenReturn(Optional.empty());

        assertThat(new ProductContentSnapshotProvider(repository, owners).load("7").orElseThrow().ownerId()).isNull();
    }

    private static Product product() {
        Product product = new Product();
        product.setName("Lámpara");
        product.setDescription("Descripción");
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(3);
        product.setCategory("Hogar");
        product.setActive(true);
        return product;
    }
}
