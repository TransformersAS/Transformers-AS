package com.transformersas.marketplace.recommendation.infrastructure;

import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.recommendation.interaction.UserInteraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GeminiRecommendationClient {

    private final RestClient restClient;

    private final String apiKey;

    private final String model;

    @Value("${gemini.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl = "https://generativelanguage.googleapis.com";


    public GeminiRecommendationClient(

        @Value("${gemini.api-key:}")
        String apiKey,

        @Value("${gemini.model:gemini-3.6-flash}")
        String model

    ) {

        this.apiKey = apiKey;

        this.model = model;

        this.restClient =
            RestClient.create();
    }


    // =========================================================
    // ¿ESTÁ CONFIGURADA LA API?
    // =========================================================

    public boolean isConfigured() {

        return apiKey != null
            && !apiKey.isBlank();
    }


    // =========================================================
    // GENERAR RECOMENDACIONES
    // =========================================================

    public List<Long> recommend(

        List<Product> candidates,

        List<UserInteraction> interactions,

        Map<Long, Product> historyProducts

    ) {

        if (!isConfigured()) {

            throw new IllegalStateException(
                "Gemini API key no configurada."
            );
        }


        String prompt =
            buildPrompt(
                candidates,
                interactions,
                historyProducts
            );


        /*
         * Queremos que Gemini responda:
         *
         * {
         *   "productIds": [4, 2, 5]
         * }
         */

        Map<String, Object> schema =
            Map.of(

                "type",
                "object",

                "properties",
                Map.of(

                    "productIds",
                    Map.of(
                        "type",
                        "array",

                        "items",
                        Map.of(
                            "type",
                            "integer"
                        ),

                        "minItems",
                        1,

                        "maxItems",
                        5
                    )
                ),

                "required",
                List.of(
                    "productIds"
                )
            );


        Map<String, Object> body =
            Map.of(

                "contents",
                List.of(

                    Map.of(

                        "parts",
                        List.of(

                            Map.of(
                                "text",
                                prompt
                            )
                        )
                    )
                ),

                "generationConfig",
                Map.of(

                    "responseFormat",
                    Map.of(

                        "text",
                        Map.of(

                            "mimeType",
                            "APPLICATION_JSON",

                            "schema",
                            schema
                        )
                    )
                )
            );


        GeminiApiResponse response =
            restClient
                .post()

                .uri(
                    baseUrl
                    + "/v1beta/models/"
                    + model
                    + ":generateContent"
                )

                .header(
                    "x-goog-api-key",
                    apiKey
                )

                .contentType(
                    MediaType.APPLICATION_JSON
                )

                .body(body)

                .retrieve()

                .body(
                    GeminiApiResponse.class
                );


        String text =
            extractText(response);


        return parseProductIds(text);
    }


    // =========================================================
    // CONSTRUIR PROMPT
    // =========================================================

    private String buildPrompt(

        List<Product> candidates,

        List<UserInteraction> interactions,

        Map<Long, Product> historyProducts

    ) {

        StringBuilder prompt =
            new StringBuilder();


        prompt.append("""
            Eres el motor de recomendaciones de un marketplace.

            Debes recomendar entre 1 y 5 productos
            basándote en el comportamiento del comprador.

            Reglas obligatorias:

            - Solo puedes recomendar IDs presentes en PRODUCTOS DISPONIBLES.
            - No inventes productos.
            - No inventes IDs.
            - Prioriza categorías relacionadas con el historial.
            - PURCHASE representa un interés fuerte.
            - ADD_TO_CART representa un interés alto.
            - VIEW representa un interés moderado.
            - SEARCH representa interés por el término buscado.
            - Si existen alternativas, evita recomendar exactamente
              el mismo producto que el usuario ya compró.

            HISTORIAL DEL COMPRADOR:
            """);


        for (
            UserInteraction interaction :
            interactions
        ) {

            prompt.append("\n- ");

            prompt.append(
                interaction
                    .getInteractionType()
            );


            if (
                interaction.getProductId()
                    != null
            ) {

                Product product =
                    historyProducts.get(
                        interaction
                            .getProductId()
                    );


                if (product != null) {

                    prompt.append(
                        " | producto: "
                    );

                    prompt.append(
                        product.getName()
                    );

                    prompt.append(
                        " | categoría: "
                    );

                    prompt.append(
                        product.getCategory()
                    );
                }
            }


            if (
                interaction.getSearchTerm()
                    != null
                &&
                !interaction
                    .getSearchTerm()
                    .isBlank()
            ) {

                prompt.append(
                    " | búsqueda: "
                );

                prompt.append(
                    interaction
                        .getSearchTerm()
                );
            }
        }


        prompt.append(
            """



            PRODUCTOS DISPONIBLES:
            """
        );


        for (Product product : candidates) {

            prompt.append("\n");

            prompt.append(
                product.getId()
            );

            prompt.append(
                " | "
            );

            prompt.append(
                product.getName()
            );

            prompt.append(
                " | categoría: "
            );

            prompt.append(
                product.getCategory()
            );

            prompt.append(
                " | descripción: "
            );

            prompt.append(
                product.getDescription()
            );
        }


        prompt.append(
            """



            Selecciona los productos más relevantes
            para este comprador.
            """
        );


        return prompt.toString();
    }


    // =========================================================
    // EXTRAER TEXTO DE GEMINI
    // =========================================================

    private String extractText(
        GeminiApiResponse response
    ) {

        if (
            response == null
            ||
            response.candidates() == null
            ||
            response.candidates().isEmpty()
        ) {

            throw new IllegalStateException(
                "Gemini no devolvió candidatos."
            );
        }


        GeminiCandidate candidate =
            response
                .candidates()
                .getFirst();


        if (
            candidate.content() == null
            ||
            candidate.content().parts() == null
            ||
            candidate
                .content()
                .parts()
                .isEmpty()
        ) {

            throw new IllegalStateException(
                "Gemini no devolvió contenido."
            );
        }


        String text =
            candidate
                .content()
                .parts()
                .getFirst()
                .text();


        if (
            text == null
            ||
            text.isBlank()
        ) {

            throw new IllegalStateException(
                "Gemini devolvió una respuesta vacía."
            );
        }


        return text;
    }


    // =========================================================
    // PARSEAR IDS
    // =========================================================

    private List<Long> parseProductIds(
        String json
    ) {

        /*
         * Gemini responde aproximadamente:
         *
         * {"productIds":[2,4,5]}
         *
         * Como esta respuesta está restringida
         * por schema, extraemos los enteros.
         */

        Pattern arrayPattern =
            Pattern.compile(
                "\\[([^\\]]*)\\]"
            );


        Matcher arrayMatcher =
            arrayPattern.matcher(json);


        if (!arrayMatcher.find()) {

            throw new IllegalStateException(
                "Respuesta de Gemini inválida: "
                + json
            );
        }


        String content =
            arrayMatcher.group(1);


        Pattern numberPattern =
            Pattern.compile("\\d+");


        Matcher numberMatcher =
            numberPattern.matcher(
                content
            );


        Set<Long> ids =
            new LinkedHashSet<>();


        while (numberMatcher.find()) {

            ids.add(
                Long.parseLong(
                    numberMatcher.group()
                )
            );
        }


        if (ids.isEmpty()) {

            throw new IllegalStateException(
                "Gemini no recomendó productos."
            );
        }


        return new ArrayList<>(ids);
    }


    // =========================================================
    // DTOs INTERNOS DE GEMINI
    // =========================================================

    private record GeminiApiResponse(

        List<GeminiCandidate> candidates

    ) {
    }


    private record GeminiCandidate(

        GeminiContent content

    ) {
    }


    private record GeminiContent(

        List<GeminiPart> parts

    ) {
    }


    private record GeminiPart(

        String text

    ) {
    }
}
