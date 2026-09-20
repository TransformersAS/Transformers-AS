-- CU-14: publicar y mantener productos del catálogo.
-- El estado (DRAFT, ACTIVE, PAUSED, RETIRED) convive con products.active: active es true solo cuando
-- el estado es ACTIVE, así carrito, reservas y checkout siguen funcionando sin cambios.
ALTER TABLE products
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN brand_id BIGINT NULL,
    ADD CONSTRAINT fk_products_brand FOREIGN KEY (brand_id) REFERENCES brands (id),
    ADD CONSTRAINT chk_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'RETIRED'));

-- Los productos que ya estaban inactivos pasan a PAUSED; los activos quedan ACTIVE por el valor por defecto.
UPDATE products SET status = 'PAUSED' WHERE active = FALSE;

-- Imágenes como enlaces; la posición 0 es la imagen principal.
CREATE TABLE product_images (
    product_id BIGINT NOT NULL,
    position INT NOT NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (product_id, position),
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products (id)
);

-- Valores de atributo (creados en CU-17) que describen el producto, por ejemplo Color: Rojo.
CREATE TABLE product_attribute_values (
    product_id BIGINT NOT NULL,
    attribute_value_id BIGINT NOT NULL,
    PRIMARY KEY (product_id, attribute_value_id),
    CONSTRAINT fk_product_attribute_values_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_product_attribute_values_value FOREIGN KEY (attribute_value_id) REFERENCES attribute_values (id)
);

-- Variantes del producto (por ejemplo, una talla) con su propio precio e inventario.
CREATE TABLE product_variants (
    product_id BIGINT NOT NULL,
    position INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(19, 2) NOT NULL,
    stock INT NOT NULL,
    PRIMARY KEY (product_id, position),
    CONSTRAINT fk_product_variants_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_product_variants_price CHECK (price >= 0),
    CONSTRAINT chk_product_variants_stock CHECK (stock >= 0)
);
