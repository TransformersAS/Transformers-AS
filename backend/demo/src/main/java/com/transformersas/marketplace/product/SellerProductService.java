package com.transformersas.marketplace.product;

import com.transformersas.marketplace.catalog.AttributeValueRepository;
import com.transformersas.marketplace.catalog.Brand;
import com.transformersas.marketplace.catalog.BrandRepository;
import com.transformersas.marketplace.catalog.CategoryRepository;
import com.transformersas.marketplace.product.dto.SellerProductRequest;
import com.transformersas.marketplace.product.dto.SellerProductResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

/**
 * Publicar y mantener los productos de una tienda (CU-14). Todos los métodos reciben el id de la tienda del
 * vendedor autenticado y solo trabajan con productos de esa tienda.
 */
@Service
@Transactional
public class SellerProductService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final AttributeValueRepository attributeValues;

    public SellerProductService(ProductRepository products, CategoryRepository categories, BrandRepository brands,
                                AttributeValueRepository attributeValues) {
        this.products = products;
        this.categories = categories;
        this.brands = brands;
        this.attributeValues = attributeValues;
    }

    /** Busca por texto (en el nombre o la categoría) y/o por estado; sin filtros devuelve todos, del más nuevo al más antiguo. */
    @Transactional(readOnly = true)
    public List<SellerProductResponse> search(Long storeId, String text, ProductStatus status) {
        String wanted = text == null ? "" : text.strip().toLowerCase();
        return products.findByStoreId(storeId).stream()
                .filter(product -> status == null || product.getStatus() == status)
                .filter(product -> product.getName().toLowerCase().contains(wanted)
                        || product.getCategory().toLowerCase().contains(wanted))
                .sorted(Comparator.comparing(Product::getId).reversed())
                .map(SellerProductResponse::from)
                .toList();
    }

    /** Sirve también como vista previa: muestra la publicación tal como quedará. */
    @Transactional(readOnly = true)
    public SellerProductResponse get(Long storeId, Long id) {
        return SellerProductResponse.from(find(storeId, id));
    }

    /** Todo producto nuevo empieza como borrador. */
    public SellerProductResponse create(Long storeId, SellerProductRequest request) {
        Product product = new Product();
        product.setStoreId(storeId);
        product.changeStatus(ProductStatus.DRAFT);
        copyRequestInto(product, request);
        return SellerProductResponse.from(products.save(product));
    }

    public SellerProductResponse update(Long storeId, Long id, SellerProductRequest request) {
        Product product = find(storeId, id);
        if (product.getStatus() == ProductStatus.RETIRED) {
            throw conflict("Un producto retirado no se puede editar");
        }
        copyRequestInto(product, request);
        return SellerProductResponse.from(products.save(product));
    }

    /** DRAFT -> ACTIVE. Exige al menos una imagen y una categoría que siga activa. */
    public SellerProductResponse publish(Long storeId, Long id) {
        Product product = find(storeId, id);
        if (product.getStatus() != ProductStatus.DRAFT) {
            throw conflict("Solo un borrador se puede publicar");
        }
        if (product.getImageUrls().isEmpty()) {
            throw conflict("Agrega al menos una imagen antes de publicar");
        }
        checkCategoryIsActive(product.getCategory());
        product.changeStatus(ProductStatus.ACTIVE);
        return SellerProductResponse.from(products.save(product));
    }

    /** ACTIVE -> PAUSED: deja de poder comprarse, pero se puede reactivar. */
    public SellerProductResponse pause(Long storeId, Long id) {
        Product product = find(storeId, id);
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw conflict("Solo un producto activo se puede pausar");
        }
        product.changeStatus(ProductStatus.PAUSED);
        return SellerProductResponse.from(products.save(product));
    }

    /** PAUSED -> ACTIVE. */
    public SellerProductResponse reactivate(Long storeId, Long id) {
        Product product = find(storeId, id);
        if (product.getStatus() != ProductStatus.PAUSED) {
            throw conflict("Solo un producto pausado se puede reactivar");
        }
        checkCategoryIsActive(product.getCategory());
        product.changeStatus(ProductStatus.ACTIVE);
        return SellerProductResponse.from(products.save(product));
    }

    /** Retirar es definitivo: el producto ya no se puede editar ni reactivar. */
    public SellerProductResponse retire(Long storeId, Long id) {
        Product product = find(storeId, id);
        if (product.getStatus() == ProductStatus.RETIRED) {
            throw conflict("El producto ya está retirado");
        }
        product.changeStatus(ProductStatus.RETIRED);
        return SellerProductResponse.from(products.save(product));
    }

    /** Crea un borrador nuevo con los mismos datos, para partir de una publicación parecida. */
    public SellerProductResponse duplicate(Long storeId, Long id) {
        Product original = find(storeId, id);
        Product copy = new Product();
        copy.setStoreId(storeId);
        copy.changeStatus(ProductStatus.DRAFT);
        String name = "Copia de " + original.getName();
        copy.setName(name.length() > 255 ? name.substring(0, 255) : name);
        copy.setDescription(original.getDescription());
        copy.setPrice(original.getPrice());
        copy.setStock(original.getStock());
        copy.setCategory(original.getCategory());
        copy.setBrandId(original.getBrandId());
        copy.getImageUrls().addAll(original.getImageUrls());
        copy.getAttributeValueIds().addAll(original.getAttributeValueIds());
        original.getVariants().forEach(variant -> copy.getVariants()
                .add(new ProductVariant(variant.getName(), variant.getPrice(), variant.getStock())));
        return SellerProductResponse.from(products.save(copy));
    }

    // ---------- Métodos auxiliares ----------

    /** Solo se encuentran productos de la tienda del vendedor; los de otras tiendas aparecen como inexistentes. */
    private Product find(Long storeId, Long id) {
        return products.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
    }

    /** Valida la categoría, la marca y los atributos, y copia los datos de la solicitud al producto. */
    private void copyRequestInto(Product product, SellerProductRequest request) {
        checkCategoryIsActive(request.category().strip());
        checkBrandIsActive(request.brandId());
        List<Long> valueIds = request.attributeValueIds() == null ? List.of()
                : request.attributeValueIds().stream().distinct().toList();
        if (attributeValues.findAllById(valueIds).size() != valueIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alguno de los valores de atributo no existe");
        }

        product.setName(request.name().strip());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setCategory(request.category().strip());
        product.setBrandId(request.brandId());

        product.getImageUrls().clear();
        if (request.imageUrls() != null) {
            product.getImageUrls().addAll(request.imageUrls());
        }
        product.getAttributeValueIds().clear();
        product.getAttributeValueIds().addAll(valueIds);
        product.getVariants().clear();
        if (request.variants() != null) {
            request.variants().forEach(variant -> product.getVariants()
                    .add(new ProductVariant(variant.name().strip(), variant.price(), variant.stock())));
        }
    }

    private void checkCategoryIsActive(String category) {
        if (!categories.existsByNameIgnoreCaseAndActiveTrue(category)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La categoría no existe o está inactiva");
        }
    }

    private void checkBrandIsActive(Long brandId) {
        if (brandId == null) {
            return;
        }
        boolean usable = brands.findById(brandId).filter(Brand::isActive).isPresent();
        if (!usable) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La marca no existe o está inactiva");
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
