CREATE TABLE user_interactions (
    id BIGINT NOT NULL AUTO_INCREMENT,

    user_id BIGINT NOT NULL,

    product_id BIGINT NULL,

    interaction_type VARCHAR(30) NOT NULL,

    search_term VARCHAR(255) NULL,

    created_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_user_interactions_product
        FOREIGN KEY (product_id)
        REFERENCES products(id)
        ON DELETE SET NULL,

    INDEX idx_interactions_user (user_id),

    INDEX idx_interactions_product (product_id),

    INDEX idx_interactions_created_at (created_at)
);