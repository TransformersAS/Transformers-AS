package com.transformersas.marketplace.recommendation.interaction;

import com.transformersas.marketplace.product.ProductRepository;
import com.transformersas.marketplace.recommendation.interaction.dto.InteractionRequest;
import com.transformersas.marketplace.recommendation.interaction.dto.InteractionResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class InteractionService {

    private final UserInteractionRepository interactionRepository;

    private final ProductRepository productRepository;


    public InteractionService(
        UserInteractionRepository interactionRepository,
        ProductRepository productRepository
    ) {

        this.interactionRepository =
            interactionRepository;

        this.productRepository =
            productRepository;
    }


    public InteractionResponse register(
        InteractionRequest request
    ) {

        if (request.userId() == null) {

            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "El userId es obligatorio."
            );
        }


        if (request.interactionType() == null) {

            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "El tipo de interacción es obligatorio."
            );
        }


        /*
         * SEARCH funciona diferente:
         * no necesita un producto específico.
         */
        if (
            request.interactionType()
                == InteractionType.SEARCH
        ) {

            if (
                request.searchTerm() == null
                ||
                request.searchTerm()
                    .isBlank()
            ) {

                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La búsqueda no puede estar vacía."
                );
            }
        }

        /*
         * VIEW, ADD_TO_CART y PURCHASE
         * necesitan un producto.
         */
        else {

            if (request.productId() == null) {

                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El productId es obligatorio."
                );
            }


            if (
                !productRepository.existsById(
                    request.productId()
                )
            ) {

                throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "El producto no existe."
                );
            }
        }


        UserInteraction interaction =
            new UserInteraction(

                request.userId(),

                request.productId(),

                request.interactionType(),

                request.searchTerm(),

                LocalDateTime.now()
            );


        UserInteraction saved =
            interactionRepository.save(
                interaction
            );


        return InteractionResponse.from(
            saved
        );
    }


    public List<InteractionResponse>
        findByUser(
            Long userId
        ) {

        return interactionRepository
            .findByUserIdOrderByCreatedAtDesc(
                userId
            )
            .stream()
            .map(
                InteractionResponse::from
            )
            .toList();
    }
}