package com.transformersas.marketplace.product;

import jakarta.validation.Valid;
import com.transformersas.marketplace.product.dto.ProductRequest;
import com.transformersas.marketplace.product.dto.ProductResponse;
import com.transformersas.marketplace.product.dto.ProductDetailResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = "*")
public class ProductController {

    private final ProductService productService;
    private final ProductDetailService details;

    public ProductController(ProductService productService, ProductDetailService details) {
        this.productService = productService;
        this.details = details;
    }

    @GetMapping
    public List<ProductResponse> getAllProducts() {
        return productService.getAllProducts();
    }

    @GetMapping("/{id}")
    public ProductDetailResponse getProductById(@PathVariable Long id) {
        return details.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@Valid @RequestBody ProductRequest product) {
        return productService.createProduct(product);
    }
}
