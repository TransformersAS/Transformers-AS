-- CU-18 F1. Continúa desde V21 (main usa V20 y V21 para el seguimiento logístico). Amplía la tienda mínima de V13: dueña, perfil, contacto, horarios, política, estado,
-- imágenes y métodos de envío habilitados. Ningún dato existente se borra ni se reescribe: la tienda 1 conserva su
-- id y su nombre, y las columnas nuevas toman valores por defecto.

ALTER TABLE stores
    ADD COLUMN owner_account_id BIGINT NULL,
    ADD COLUMN description VARCHAR(1000) NULL,
    ADD COLUMN contact_email VARCHAR(254) NULL,
    ADD COLUMN contact_phone VARCHAR(50) NULL,
    ADD COLUMN business_hours VARCHAR(500) NULL,
    ADD COLUMN return_window_days INT NOT NULL DEFAULT 30,
    ADD COLUMN policy_text VARCHAR(2000) NULL,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN status_reason VARCHAR(500) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    ADD CONSTRAINT uk_stores_name UNIQUE (name),
    ADD CONSTRAINT uk_stores_owner UNIQUE (owner_account_id),
    ADD CONSTRAINT fk_stores_owner FOREIGN KEY (owner_account_id) REFERENCES user_accounts (id),
    ADD CONSTRAINT chk_stores_return_window CHECK (return_window_days >= 30),
    ADD CONSTRAINT chk_stores_status CHECK (status IN ('ACTIVE', 'RESTRICTED', 'SUSPENDED')),
    ADD CONSTRAINT chk_stores_status_reason CHECK (status = 'ACTIVE' OR status_reason IS NOT NULL);

-- Una imagen por tipo y tienda. El binario vive aquí y se sirve por un endpoint aparte; sha256 es el ETag.
-- 5242880 bytes = 5 MiB.
CREATE TABLE store_images (
    store_id BIGINT NOT NULL,
    kind VARCHAR(10) NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    size_bytes INT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data MEDIUMBLOB NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (store_id, kind),
    CONSTRAINT fk_store_images_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT chk_store_images_kind CHECK (kind IN ('LOGO', 'PORTADA')),
    CONSTRAINT chk_store_images_content_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT chk_store_images_size CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);

-- Métodos de envío que la tienda ofrece. method usa el mismo ancho que orders.shipping_method.
CREATE TABLE store_shipping_methods (
    store_id BIGINT NOT NULL,
    method VARCHAR(30) NOT NULL,
    PRIMARY KEY (store_id, method),
    CONSTRAINT fk_store_shipping_methods_store FOREIGN KEY (store_id) REFERENCES stores (id)
);

-- La tienda 1 conserva el comportamiento actual del checkout: ofrece STANDARD y EXPRESS.
INSERT INTO store_shipping_methods (store_id, method) VALUES (1, 'STANDARD'), (1, 'EXPRESS');
