package com.transformersas.marketplace.recommendation.infrastructure;

import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.recommendation.interaction.InteractionType;
import com.transformersas.marketplace.recommendation.interaction.UserInteraction;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiRecommendationClientTests {

    // =========================================================
    // CONFIGURACIÓN
    // =========================================================

    @Test
    void detectsWhetherGeminiApiKeyIsConfigured() {

        assertThat(
                new GeminiRecommendationClient(
                        null,
                        "gemini-test"
                ).isConfigured()
        ).isFalse();

        assertThat(
                new GeminiRecommendationClient(
                        "",
                        "gemini-test"
                ).isConfigured()
        ).isFalse();

        assertThat(
                new GeminiRecommendationClient(
                        "   ",
                        "gemini-test"
                ).isConfigured()
        ).isFalse();

        assertThat(
                new GeminiRecommendationClient(
                        "test-api-key",
                        "gemini-test"
                ).isConfigured()
        ).isTrue();
    }


    @Test
    void recommendRejectsExecutionWithoutApiKey() {

        GeminiRecommendationClient client =
                new GeminiRecommendationClient(
                        "",
                        "gemini-test"
                );

        assertThatThrownBy(
                () -> client.recommend(
                        List.of(),
                        List.of(),
                        Map.of()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "API key no configurada"
                );
    }


    // =========================================================
    // PROMPT
    // =========================================================

    @Test
    void buildsPromptUsingHistorySearchesAndAvailableProducts()
            throws Exception {

        GeminiRecommendationClient client =
                client();


        Product historyProduct =
                product(
                        1L,
                        "Mouse Gamer",
                        "Tecnología",
                        "Mouse inalámbrico"
                );

        Product candidate1 =
                product(
                        2L,
                        "Teclado Mecánico",
                        "Tecnología",
                        "Teclado RGB"
                );

        Product candidate2 =
                product(
                        3L,
                        "Audífonos",
                        "Tecnología",
                        "Audífonos Bluetooth"
                );


        UserInteraction purchase =
                interaction(
                        InteractionType.PURCHASE,
                        1L,
                        null
                );

        /*
         * Este producto no está dentro de historyProducts.
         * Así también cubrimos ese camino.
         */
        UserInteraction viewUnknownProduct =
                interaction(
                        InteractionType.VIEW,
                        999L,
                        "   "
                );

        UserInteraction search =
                interaction(
                        InteractionType.SEARCH,
                        null,
                        "tecnología"
                );


        String prompt =
                invokeBuildPrompt(
                        client,
                        List.of(
                                candidate1,
                                candidate2
                        ),
                        List.of(
                                purchase,
                                viewUnknownProduct,
                                search
                        ),
                        Map.of(
                                1L,
                                historyProduct
                        )
                );


        assertThat(prompt)
                .contains(
                        "HISTORIAL DEL COMPRADOR"
                )
                .contains(
                        "PURCHASE"
                )
                .contains(
                        "Mouse Gamer"
                )
                .contains(
                        "tecnología"
                )
                .contains(
                        "PRODUCTOS DISPONIBLES"
                )
                .contains(
                        "Teclado Mecánico"
                )
                .contains(
                        "Audífonos"
                );
    }


    // =========================================================
    // PARSEO DE IDS
    // =========================================================

    @Test
    void parsesProductIdsAndRemovesDuplicates()
            throws Exception {

        GeminiRecommendationClient client =
                client();


        List<Long> ids =
                invokeParseProductIds(
                        client,
                        """
                        {
                          "productIds": [2, 4, 2, 5, 8]
                        }
                        """
                );


        assertThat(ids)
                .containsExactly(
                        2L,
                        4L,
                        5L,
                        8L
                );
    }


   @Test
void rejectsGeminiJsonWithoutArray() {

    GeminiRecommendationClient client =
            client();

    String invalidJson =
            "{\"productIds\": null}";

    assertThatThrownBy(
            () -> invokeParseProductIds(
                    client,
                    invalidJson
            )
    )
            .hasRootCauseInstanceOf(
                    IllegalStateException.class
            )
            .hasRootCauseMessage(
                    "Respuesta de Gemini inválida: "
                            + invalidJson
            );
}


    @Test
    void rejectsGeminiJsonWithEmptyProductArray() {

        GeminiRecommendationClient client =
                client();


        assertThatThrownBy(
                () -> invokeParseProductIds(
                        client,
                        """
                        {"productIds":[]}
                        """
                )
        )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Gemini no recomendó productos."
                );
    }


    // =========================================================
    // EXTRACCIÓN DE RESPUESTA
    // =========================================================

    @Test
    void extractTextReturnsGeminiText()
            throws Exception {

        GeminiRecommendationClient client =
                client();

        Object response =
                responseWithText(
                        """
                        {"productIds":[1,2,3]}
                        """
                );


        String text =
                invokeExtractText(
                        client,
                        response
                );


        assertThat(text)
                .contains(
                        "\"productIds\""
                )
                .contains(
                        "1,2,3"
                );
    }


    @Test
    void extractTextRejectsMissingCandidates()
            throws Exception {

        GeminiRecommendationClient client =
                client();


        /*
         * response == null
         */
        assertThatThrownBy(
                () -> invokeExtractText(
                        client,
                        null
                )
        )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Gemini no devolvió candidatos."
                );


        /*
         * candidates == null
         */
        Object nullCandidates =
                apiResponse(null);

        assertThatThrownBy(
                () -> invokeExtractText(
                        client,
                        nullCandidates
                )
        )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Gemini no devolvió candidatos."
                );


        /*
         * candidates vacíos
         */
        Object emptyCandidates =
                apiResponse(List.of());

        assertThatThrownBy(
                () -> invokeExtractText(
                        client,
                        emptyCandidates
                )
        )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Gemini no devolvió candidatos."
                );
    }


    @Test
    void extractTextRejectsMissingContent()
            throws Exception {

        GeminiRecommendationClient client =
                client();


        /*
         * candidate.content() == null
         */
        Object candidateWithoutContent =
                candidate(null);

        Object responseWithoutContent =
                apiResponse(
                        List.of(
                                candidateWithoutContent
                        )
                );


        assertThatThrownBy(
                () -> invokeExtractText(
                        client,
                        responseWithoutContent
                )
        )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Gemini no devolvió contenido."
                );


        /*
         * content.parts() == null
         */
        Object contentWithoutParts =
                content(null);

        Object candidateWithoutParts =
                candidate(
                        contentWithoutParts
                );

        Object responseWithoutParts =
                apiResponse(
                        List.of(
                                candidateWithoutParts
                        )
                );


        assertThatThrownBy(
                () -> invokeExtractText(
                        client,
                        responseWithoutParts
                )
        )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Gemini no devolvió contenido."
                );


        /*
         * parts vacíos
         */
        Object emptyContent =
                content(List.of());

        Object candidateWithEmptyParts =
                candidate(
                        emptyContent
                );

        Object responseWithEmptyParts =
                apiResponse(
                        List.of(
                                candidateWithEmptyParts
                        )
                );


        assertThatThrownBy(
                () -> invokeExtractText(
                        client,
                        responseWithEmptyParts
                )
        )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Gemini no devolvió contenido."
                );
    }


    @Test
    void extractTextRejectsNullOrBlankText()
            throws Exception {

        GeminiRecommendationClient client =
                client();


        Object nullTextResponse =
                responseWithText(null);


        assertThatThrownBy(
                () -> invokeExtractText(
                        client,
                        nullTextResponse
                )
        )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Gemini devolvió una respuesta vacía."
                );


        Object blankTextResponse =
                responseWithText("   ");


        assertThatThrownBy(
                () -> invokeExtractText(
                        client,
                        blankTextResponse
                )
        )
                .hasRootCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "Gemini devolvió una respuesta vacía."
                );
    }


    // =========================================================
    // HELPERS
    // =========================================================

    private GeminiRecommendationClient client() {

        return new GeminiRecommendationClient(
                "test-api-key",
                "gemini-test"
        );
    }


    private Product product(
            Long id,
            String name,
            String category,
            String description
    ) {

        Product product =
                mock(Product.class);


        when(product.getId())
                .thenReturn(id);

        when(product.getName())
                .thenReturn(name);

        when(product.getCategory())
                .thenReturn(category);

        when(product.getDescription())
                .thenReturn(description);

        when(product.getPrice())
                .thenReturn(
                        new BigDecimal("100.00")
                );

        when(product.getStock())
                .thenReturn(10);


        return product;
    }


    private UserInteraction interaction(
            InteractionType type,
            Long productId,
            String searchTerm
    ) {

        UserInteraction interaction =
                mock(UserInteraction.class);


        when(
                interaction.getInteractionType()
        ).thenReturn(type);

        when(
                interaction.getProductId()
        ).thenReturn(productId);

        when(
                interaction.getSearchTerm()
        ).thenReturn(searchTerm);


        return interaction;
    }


    @SuppressWarnings("unchecked")
    private List<Long> invokeParseProductIds(
            GeminiRecommendationClient client,
            String json
    ) throws Exception {

        Method method =
                GeminiRecommendationClient.class
                        .getDeclaredMethod(
                                "parseProductIds",
                                String.class
                        );

        method.setAccessible(true);


        return (List<Long>) method.invoke(
                client,
                json
        );
    }


    private String invokeBuildPrompt(
            GeminiRecommendationClient client,
            List<Product> candidates,
            List<UserInteraction> interactions,
            Map<Long, Product> historyProducts
    ) throws Exception {

        Method method =
                GeminiRecommendationClient.class
                        .getDeclaredMethod(
                                "buildPrompt",
                                List.class,
                                List.class,
                                Map.class
                        );

        method.setAccessible(true);


        return (String) method.invoke(
                client,
                candidates,
                interactions,
                historyProducts
        );
    }


    private String invokeExtractText(
            GeminiRecommendationClient client,
            Object response
    ) throws Exception {

        Class<?> responseClass =
                nestedClass(
                        "GeminiApiResponse"
                );


        Method method =
                GeminiRecommendationClient.class
                        .getDeclaredMethod(
                                "extractText",
                                responseClass
                        );

        method.setAccessible(true);


        return (String) method.invoke(
                client,
                response
        );
    }


    private Object responseWithText(
            String text
    ) throws Exception {

        Object part =
                part(text);

        Object content =
                content(
                        List.of(part)
                );

        Object candidate =
                candidate(content);


        return apiResponse(
                List.of(candidate)
        );
    }


    private Object part(
            String text
    ) throws Exception {

        Class<?> partClass =
                nestedClass(
                        "GeminiPart"
                );


        Constructor<?> constructor =
                partClass
                        .getDeclaredConstructor(
                                String.class
                        );

        constructor.setAccessible(true);


        return constructor
                .newInstance(text);
    }


    private Object content(
            List<?> parts
    ) throws Exception {

        Class<?> contentClass =
                nestedClass(
                        "GeminiContent"
                );


        Constructor<?> constructor =
                contentClass
                        .getDeclaredConstructor(
                                List.class
                        );

        constructor.setAccessible(true);


        return constructor
                .newInstance(parts);
    }


    private Object candidate(
            Object content
    ) throws Exception {

        Class<?> candidateClass =
                nestedClass(
                        "GeminiCandidate"
                );

        Class<?> contentClass =
                nestedClass(
                        "GeminiContent"
                );


        Constructor<?> constructor =
                candidateClass
                        .getDeclaredConstructor(
                                contentClass
                        );

        constructor.setAccessible(true);


        return constructor
                .newInstance(content);
    }


    private Object apiResponse(
            List<?> candidates
    ) throws Exception {

        Class<?> responseClass =
                nestedClass(
                        "GeminiApiResponse"
                );


        Constructor<?> constructor =
                responseClass
                        .getDeclaredConstructor(
                                List.class
                        );

        constructor.setAccessible(true);


        return constructor
                .newInstance(candidates);
    }


    private Class<?> nestedClass(
            String simpleName
    ) throws ClassNotFoundException {

        return Class.forName(
                GeminiRecommendationClient.class
                        .getName()
                        + "$"
                        + simpleName
        );
    }
}