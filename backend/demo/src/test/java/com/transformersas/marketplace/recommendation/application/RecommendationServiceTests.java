package com.transformersas.marketplace.recommendation.application;

import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductRepository;
import com.transformersas.marketplace.recommendation.dto.RecommendationResponse;
import com.transformersas.marketplace.recommendation.infrastructure.GeminiRecommendationClient;
import com.transformersas.marketplace.recommendation.interaction.UserInteraction;
import com.transformersas.marketplace.recommendation.interaction.UserInteractionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTests {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserInteractionRepository interactionRepository;

    @Mock
    private GeminiRecommendationClient geminiClient;

    private RecommendationService service;


    @BeforeEach
    void setUp() {

        service = new RecommendationService(
                productRepository,
                interactionRepository,
                geminiClient
        );
    }


    @Test
    void returnsNoProductsWhenThereAreNoAvailableCandidates() {

        when(
                productRepository
                        .findByActiveTrueAndStockGreaterThan(0)
        ).thenReturn(List.of());


        RecommendationResponse response =
                service.recommend(1L);


        assertThat(response.userId())
                .isEqualTo(1L);

        assertThat(response.strategy())
                .isEqualTo("NO_PRODUCTS");

        assertThat(response.products())
                .isEmpty();


        verifyNoInteractions(
                interactionRepository,
                geminiClient
        );
    }


    @Test
    void returnsGeneralRecommendationsWhenUserHasNoHistory() {

        Product p1 = product(1L, "Producto 1", 10);
        Product p2 = product(2L, "Producto 2", 50);
        Product p3 = product(3L, "Producto 3", 30);
        Product p4 = product(4L, "Producto 4", 20);
        Product p5 = product(5L, "Producto 5", 40);
        Product p6 = product(6L, "Producto 6", 60);


        when(
                productRepository
                        .findByActiveTrueAndStockGreaterThan(0)
        ).thenReturn(
                List.of(
                        p1,
                        p2,
                        p3,
                        p4,
                        p5,
                        p6
                )
        );


        when(
                interactionRepository
                        .findByUserIdOrderByCreatedAtDesc(1L)
        ).thenReturn(List.of());


        RecommendationResponse response =
                service.recommend(1L);


        assertThat(response.strategy())
                .isEqualTo("GENERAL_NO_HISTORY");

        /*
         * El fallback ordena por stock de mayor a menor
         * y devuelve máximo 5 productos.
         */
        assertThat(response.products())
                .hasSize(5);

        assertThat(
                response.products()
                        .stream()
                        .map(product -> product.id())
                        .toList()
        ).containsExactly(
                6L,
                2L,
                5L,
                3L,
                4L
        );


        verifyNoInteractions(geminiClient);
    }


    @Test
    void returnsGeneralRecommendationsWhenGeminiIsNotConfigured() {

        Product p1 =
                product(1L, "Producto 1", 10);

        Product p2 =
                product(2L, "Producto 2", 20);

        UserInteraction interaction =
                interaction(1L);


        when(
                productRepository
                        .findByActiveTrueAndStockGreaterThan(0)
        ).thenReturn(
                List.of(p1, p2)
        );


        when(
                interactionRepository
                        .findByUserIdOrderByCreatedAtDesc(1L)
        ).thenReturn(
                List.of(interaction)
        );


        when(
                productRepository.findAllById(any())
        ).thenReturn(
                List.of(p1)
        );


        when(
                geminiClient.isConfigured()
        ).thenReturn(false);


        RecommendationResponse response =
                service.recommend(1L);


        assertThat(response.strategy())
                .isEqualTo("GENERAL_NO_API_KEY");

        assertThat(response.products())
                .hasSize(2);

        assertThat(response.products().get(0).id())
                .isEqualTo(2L);


        verify(
                geminiClient,
                never()
        ).recommend(
                any(),
                any(),
                any()
        );
    }


    @Test
    void returnsAiRecommendationsAndIgnoresInvalidAndDuplicateIds() {

        Product p1 =
                product(1L, "Producto 1", 10);

        Product p2 =
                product(2L, "Producto 2", 20);

        Product p3 =
                product(3L, "Producto 3", 30);

        Product p4 =
                product(4L, "Producto 4", 40);

        Product p5 =
                product(5L, "Producto 5", 50);

        Product p6 =
                product(6L, "Producto 6", 60);


        List<Product> candidates =
                List.of(
                        p1,
                        p2,
                        p3,
                        p4,
                        p5,
                        p6
                );


        UserInteraction interaction =
                interaction(1L);


        when(
                productRepository
                        .findByActiveTrueAndStockGreaterThan(0)
        ).thenReturn(candidates);


        when(
                interactionRepository
                        .findByUserIdOrderByCreatedAtDesc(1L)
        ).thenReturn(
                List.of(interaction)
        );


        when(
                productRepository.findAllById(any())
        ).thenReturn(
                List.of(p1)
        );


        when(
                geminiClient.isConfigured()
        ).thenReturn(true);


        /*
         * 999 no existe.
         * 3 aparece repetido.
         * Hay 6 productos válidos, pero el servicio
         * solo debe retornar máximo 5.
         */
        when(
                geminiClient.recommend(
                        any(),
                        any(),
                        any()
                )
        ).thenReturn(
                List.of(
                        3L,
                        999L,
                        3L,
                        2L,
                        1L,
                        4L,
                        5L,
                        6L
                )
        );


        RecommendationResponse response =
                service.recommend(1L);


        assertThat(response.strategy())
                .isEqualTo("AI");


        assertThat(response.products())
                .hasSize(5);


        assertThat(
                response.products()
                        .stream()
                        .map(product -> product.id())
                        .toList()
        ).containsExactly(
                3L,
                2L,
                1L,
                4L,
                5L
        );
    }


    @Test
    void fallsBackWhenGeminiReturnsNoValidProducts() {

        Product p1 =
                product(1L, "Producto 1", 10);

        Product p2 =
                product(2L, "Producto 2", 30);


        UserInteraction interaction =
                interaction(null);


        when(
                productRepository
                        .findByActiveTrueAndStockGreaterThan(0)
        ).thenReturn(
                List.of(p1, p2)
        );


        when(
                interactionRepository
                        .findByUserIdOrderByCreatedAtDesc(1L)
        ).thenReturn(
                List.of(interaction)
        );


        /*
         * La interacción tiene productId null,
         * así cubrimos también el filtro de IDs.
         */
        when(
                productRepository.findAllById(any())
        ).thenReturn(
                List.of()
        );


        when(
                geminiClient.isConfigured()
        ).thenReturn(true);


        when(
                geminiClient.recommend(
                        any(),
                        any(),
                        any()
                )
        ).thenReturn(
                List.of(999L)
        );


        RecommendationResponse response =
                service.recommend(1L);


        assertThat(response.strategy())
                .isEqualTo("GENERAL_AI_EMPTY");


        assertThat(response.products())
                .hasSize(2);


        assertThat(response.products().get(0).id())
                .isEqualTo(2L);
    }


    @Test
    void fallsBackWhenGeminiThrowsException() {

        Product p1 =
                product(1L, "Producto 1", 10);

        Product p2 =
                product(2L, "Producto 2", 30);


        UserInteraction interaction =
                interaction(1L);


        when(
                productRepository
                        .findByActiveTrueAndStockGreaterThan(0)
        ).thenReturn(
                List.of(p1, p2)
        );


        when(
                interactionRepository
                        .findByUserIdOrderByCreatedAtDesc(1L)
        ).thenReturn(
                List.of(interaction)
        );


        when(
                productRepository.findAllById(any())
        ).thenReturn(
                List.of(p1)
        );


        when(
                geminiClient.isConfigured()
        ).thenReturn(true);


        when(
                geminiClient.recommend(
                        any(),
                        any(),
                        any()
                )
        ).thenThrow(
                new RuntimeException(
                        "Gemini temporalmente no disponible"
                )
        );


        RecommendationResponse response =
                service.recommend(1L);


        assertThat(response.strategy())
                .isEqualTo(
                        "GENERAL_AI_UNAVAILABLE"
                );


        assertThat(response.products())
                .hasSize(2);


        assertThat(response.products().get(0).id())
                .isEqualTo(2L);
    }

private Product product(
        long id,
        String name,
        int stock
) {

    Product product =
            mock(Product.class);


    lenient()
            .when(product.getId())
            .thenReturn(id);

    lenient()
            .when(product.getName())
            .thenReturn(name);

    lenient()
            .when(product.getDescription())
            .thenReturn(
                    "Descripción " + name
            );

    lenient()
            .when(product.getPrice())
            .thenReturn(
                    BigDecimal.valueOf(
                            id * 100
                    )
            );

    lenient()
            .when(product.getStock())
            .thenReturn(stock);

    lenient()
            .when(product.getCategory())
            .thenReturn("Test");


    return product;
}

    private UserInteraction interaction(
            Long productId
    ) {

        UserInteraction interaction =
                mock(UserInteraction.class);


        when(
                interaction.getProductId()
        ).thenReturn(productId);


        return interaction;
    }
}