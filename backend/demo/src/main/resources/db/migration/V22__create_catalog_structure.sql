-- CU-06: estructura del catálogo (categorías, marcas y atributos con sus valores permitidos).
-- products.category sigue siendo texto: por ahora se compara por nombre para saber si una categoría está en uso.

CREATE TABLE categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    -- NULL = categoría principal; con valor = subcategoría de esa categoría.
    parent_id BIGINT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id)
);

CREATE TABLE brands (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE attributes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

-- Valores permitidos de un atributo (por ejemplo, Color: Rojo, Azul).
CREATE TABLE attribute_values (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    attribute_id BIGINT NOT NULL,
    value_text VARCHAR(100) NOT NULL,
    CONSTRAINT fk_attribute_values_attribute FOREIGN KEY (attribute_id) REFERENCES attributes (id),
    CONSTRAINT uq_attribute_values UNIQUE (attribute_id, value_text)
);
