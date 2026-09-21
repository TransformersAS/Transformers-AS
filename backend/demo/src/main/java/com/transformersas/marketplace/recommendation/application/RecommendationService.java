package com.transformersas.marketplace.recommendation.application;

import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductRepository;

import com.transformersas.marketplace.recommendation.dto.RecommendationResponse;
import com.transformersas.marketplace.recommendation.dto.RecommendedProductResponse;

import com.transformersas.marketplace.recommendation.infrastructure.GeminiRecommendationClient;

import com.transformersas.marketplace.recommendation.interaction.UserInteraction;
import com.transformersas.marketplace.recommendation.interaction.UserInteractionRepository;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RecommendationService {

    private static final int MAX_RECOMMENDATIONS =
        5;


    private final ProductRepository productRepository;

    private final UserInteractionRepository
        interactionRepository;

    private final GeminiRecommendationClient
        geminiClient;


    public RecommendationService(

        ProductRepository productRepository,

        UserInteractionRepository
            interactionRepository,

        GeminiRecommendationClient
            geminiClient

    ) {

        this.productRepository =
            productRepository;

        this.interactionRepository =
            interactionRepository;

        this.geminiClient =
            geminiClient;
    }


    // =========================================================
    // OBTENER RECOMENDACIONES
    // =========================================================

    public RecommendationResponse recommend(
        Long userId
    ) {

        /*
         * Solo damos a Gemini productos
         * que realmente pueden comprarse.
         */
        List<Product> candidates =
            productRepository
                .findByActiveTrueAndStockGreaterThan(
                    0
                );


        if (candidates.isEmpty()) {

            return new RecommendationResponse(

                userId,

                "NO_PRODUCTS",

                List.of()
            );
        }


        List<UserInteraction> interactions =
            interactionRepository
                .findByUserIdOrderByCreatedAtDesc(
                    userId
                );


        /*
         * A1:
         *
         * Si el usuario todavía no tiene historial,
         * mostramos recomendaciones generales.
         */
        if (interactions.isEmpty()) {

            return fallback(

                userId,

                candidates,

                "GENERAL_NO_HISTORY"
            );
        }


        /*
         * Recuperamos información de los productos
         * con los que el usuario interactuó.
         */
        Set<Long> historyProductIds =
            interactions
                .stream()

                .map(
                    UserInteraction::getProductId
                )

                .filter(
                    id -> id != null
                )

                .collect(
                    Collectors.toSet()
                );


        Map<Long, Product> historyProducts =
            productRepository
                .findAllById(
                    historyProductIds
                )

                .stream()

                .collect(
                    Collectors.toMap(
                        Product::getId,
                        product -> product
                    )
                );


        /*
         * Si todavía no configuramos la API key,
         * el marketplace sigue funcionando.
         */
        if (!geminiClient.isConfigured()) {

            return fallback(

                userId,

                candidates,

                "GENERAL_NO_API_KEY"
            );
        }


        try {

            List<Long> recommendedIds =
                geminiClient.recommend(

                    candidates,

                    interactions,

                    historyProducts
                );


            /*
             * Creamos un mapa únicamente con
             * productos válidos del catálogo.
             */
            Map<Long, Product> candidateMap =
                candidates
                    .stream()

                    .collect(
                        Collectors.toMap(

                            Product::getId,

                            product ->
                                product
                        )
                    );


            /*
             * Muy importante:
             *
             * aunque Gemini devolviera un ID extraño,
             * nosotros NO lo mostramos.
             *
             * Solo aceptamos IDs que existan
             * dentro de candidates.
             */
            Map<Long, Product> selected =
                new LinkedHashMap<>();


            for (
                Long id :
                recommendedIds
            ) {

                Product product =
                    candidateMap.get(id);


                if (product != null) {

                    selected.putIfAbsent(
                        id,
                        product
                    );
                }


                if (
                    selected.size()
                    >= MAX_RECOMMENDATIONS
                ) {

                    break;
                }
            }


            if (selected.isEmpty()) {

                return fallback(

                    userId,

                    candidates,

                    "GENERAL_AI_EMPTY"
                );
            }


            List<RecommendedProductResponse>
                products =

                selected
                    .values()

                    .stream()

                    .map(
                        RecommendedProductResponse
                            ::from
                    )

                    .toList();


            return new RecommendationResponse(

                userId,

                "AI",

                products
            );


        } catch (Exception exception) {

            /*
             * A2:
             *
             * Si Gemini se cae,
             * el marketplace NO se cae.
             */
            System.err.println(
                "Gemini no disponible: "
                + exception.getMessage()
            );


            return fallback(

                userId,

                candidates,

                "GENERAL_AI_UNAVAILABLE"
            );
        }
    }


    // =========================================================
    // FALLBACK
    // =========================================================

    private RecommendationResponse fallback(

        Long userId,

        List<Product> candidates,

        String strategy

    ) {

        /*
         * Para el prototipo usamos como
         * recomendación general los productos
         * con mayor disponibilidad.
         */
        List<RecommendedProductResponse>
            generalProducts =

            candidates
                .stream()

                .sorted(
                    Comparator
                        .comparing(
                            Product::getStock
                        )
                        .reversed()
                )

                .limit(
                    MAX_RECOMMENDATIONS
                )

                .map(
                    RecommendedProductResponse
                        ::from
                )

                .toList();


        return new RecommendationResponse(

            userId,

            strategy,

            generalProducts
        );
    }
}