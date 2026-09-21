package com.transformersas.marketplace.checkout;

import com.transformersas.marketplace.address.AddressRepository;
import com.transformersas.marketplace.cart.Cart;
import com.transformersas.marketplace.cart.CartItem;
import com.transformersas.marketplace.cart.CartItemRepository;
import com.transformersas.marketplace.cart.CartRepository;
import com.transformersas.marketplace.checkout.dto.CheckoutPreviewRequest;
import com.transformersas.marketplace.checkout.dto.CheckoutPreviewResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class CheckoutService {

    private static final BigDecimal STANDARD_SHIPPING =
            new BigDecimal("10000");

    private static final BigDecimal EXPRESS_SHIPPING =
            new BigDecimal("20000");

    private static final BigDecimal DESC10 =
            new BigDecimal("0.10");


    private final CartRepository cartRepository;

    private final CartItemRepository cartItemRepository;

    private final AddressRepository addressRepository;


    public CheckoutService(
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            AddressRepository addressRepository
    ) {

        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.addressRepository = addressRepository;
    }


    public CheckoutPreviewResponse preview(
            CheckoutPreviewRequest request
    ) {

        // ===============================
        // 1. VALIDAR REQUEST
        // ===============================

        if (request == null) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La solicitud de checkout es obligatoria"
            );
        }


        // ===============================
        // 2. VALIDAR DIRECCIÓN
        // ===============================

        if (request.addressId() == null) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debe seleccionar una dirección de entrega"
            );
        }


        boolean addressExists =
                addressRepository.existsById(
                        request.addressId()
                );


        if (!addressExists) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "La dirección seleccionada no existe"
            );
        }


        // ===============================
        // 3. OBTENER CARRITO
        // ===============================

        Cart cart = cartRepository
                .findAll()
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "No existe un carrito"
                        )
                );


        List<CartItem> items =
                cartItemRepository.findByCartId(
                        cart.getId()
                );


        if (items.isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El carrito está vacío"
            );
        }


        // ===============================
        // 4. REVALIDAR PRODUCTOS
        // ===============================

        BigDecimal subtotal = BigDecimal.ZERO;

        /*
         * CU-03:
         *
         * El checkout actual genera un único pedido.
         * Por lo tanto, todos los productos del carrito
         * deben pertenecer a una misma tienda.
         *
         * La validación se hace en preview(),
         * ANTES de intentar procesar el pago.
         */
        Long storeId = null;


        for (CartItem item : items) {

            var product = item.getProduct();


            if (product == null) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Uno de los productos del carrito ya no existe"
                );
            }


            if (!Boolean.TRUE.equals(
                    product.getActive()
            )) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "El producto "
                                + product.getName()
                                + " ya no está disponible"
                );
            }


            if (item.getQuantity() == null
                    || item.getQuantity() <= 0) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Cantidad inválida para "
                                + product.getName()
                );
            }


            if (product.getStock() == null
                    || product.getStock()
                    < item.getQuantity()) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "No hay suficiente stock para "
                                + product.getName()
                );
            }


            // ===============================
            // 4.1 VALIDAR UNA SOLA TIENDA
            // ===============================

            Long productStoreId =
                    product.getStoreId();


            if (productStoreId == null) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "El producto "
                                + product.getName()
                                + " no tiene una tienda asociada"
                );
            }


            /*
             * El primer producto establece cuál es
             * la tienda esperada para todo el carrito.
             */
            if (storeId == null) {

                storeId = productStoreId;

            } else if (!storeId.equals(productStoreId)) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "El carrito contiene productos de diferentes tiendas"
                );
            }


            BigDecimal itemSubtotal =
                    product.getPrice()
                            .multiply(
                                    BigDecimal.valueOf(
                                            item.getQuantity()
                                    )
                            );


            subtotal =
                    subtotal.add(
                            itemSubtotal
                    );
        }


        // ===============================
        // 5. MÉTODO DE ENVÍO
        // ===============================

        BigDecimal shippingCost =
                calculateShipping(
                        request.shippingMethod()
                );


        // ===============================
        // 6. CUPÓN
        // ===============================

        CouponResult couponResult =
                calculateCoupon(
                        subtotal,
                        request.couponCode()
                );


        // ===============================
        // 7. TOTAL
        // ===============================

        BigDecimal total =
                subtotal
                        .subtract(
                                couponResult.discount()
                        )
                        .add(
                                shippingCost
                        );


        return new CheckoutPreviewResponse(
                subtotal,
                couponResult.discount(),
                shippingCost,
                total,
                couponResult.valid()
        );
    }


    private BigDecimal calculateShipping(
            String shippingMethod
    ) {

        if (shippingMethod == null
                || shippingMethod.isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debe seleccionar un método de envío"
            );
        }


        return switch (
                shippingMethod
                        .trim()
                        .toUpperCase()
        ) {

            case "STANDARD" ->
                    STANDARD_SHIPPING;

            case "EXPRESS" ->
                    EXPRESS_SHIPPING;

            default ->
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Método de envío inválido"
                    );
        };
    }


    private CouponResult calculateCoupon(
            BigDecimal subtotal,
            String couponCode
    ) {

        /*
         * Si no se ingresó cupón,
         * simplemente no aplicamos descuento.
         */
        if (couponCode == null
                || couponCode.isBlank()) {

            return new CouponResult(
                    BigDecimal.ZERO,
                    true
            );
        }


        String coupon =
                couponCode
                        .trim()
                        .toUpperCase();


        /*
         * Cupón de prueba para el prototipo:
         *
         * DESC10 = 10% de descuento.
         */
        if ("DESC10".equals(coupon)) {

            BigDecimal discount =
                    subtotal
                            .multiply(DESC10)
                            .setScale(
                                    2,
                                    RoundingMode.HALF_UP
                            );


            return new CouponResult(
                    discount,
                    true
            );
        }


        /*
         * CU-03 A3:
         *
         * Un cupón inválido NO bloquea
         * el proceso de compra.
         */
        return new CouponResult(
                BigDecimal.ZERO,
                false
        );
    }


    private record CouponResult(
            BigDecimal discount,
            boolean valid
    ) {
    }
}