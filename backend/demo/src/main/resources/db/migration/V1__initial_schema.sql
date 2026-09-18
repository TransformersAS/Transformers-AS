CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    price DECIMAL(38,2) NOT NULL,
    stock INT NOT NULL,
    category VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL,

    PRIMARY KEY (id)
);

CREATE TABLE carts (
    id BIGINT NOT NULL AUTO_INCREMENT,

    PRIMARY KEY (id)
);

CREATE TABLE cart_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    cart_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_cart_item_cart
        FOREIGN KEY (cart_id)
        REFERENCES carts(id),

    CONSTRAINT fk_cart_item_product
        FOREIGN KEY (product_id)
        REFERENCES products(id)
);