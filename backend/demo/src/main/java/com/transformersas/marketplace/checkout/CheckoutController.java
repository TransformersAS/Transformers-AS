package com.transformersas.marketplace.checkout;

import com.transformersas.marketplace.checkout.dto.CheckoutPreviewRequest;
import com.transformersas.marketplace.checkout.dto.CheckoutPreviewResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/checkout")
@CrossOrigin(origins = "*")
public class CheckoutController {

    private final CheckoutService checkoutService;


    public CheckoutController(
            CheckoutService checkoutService
    ) {

        this.checkoutService =
                checkoutService;
    }


    @PostMapping("/preview")
    public ResponseEntity<CheckoutPreviewResponse> preview(
            @RequestBody CheckoutPreviewRequest request
    ) {

        CheckoutPreviewResponse response =
                checkoutService.preview(
                        request
                );


        return ResponseEntity.ok(
                response
        );
    }
}