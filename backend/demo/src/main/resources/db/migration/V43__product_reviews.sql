CREATE TABLE product_reviews (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 account_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 rating INT NOT NULL,
 comment VARCHAR(2000) NOT NULL DEFAULT '',
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT uq_product_review_purchase UNIQUE(account_id,order_id,product_id),
 CONSTRAINT fk_review_account FOREIGN KEY(account_id) REFERENCES user_accounts(id),
 CONSTRAINT fk_review_order FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE,
 CONSTRAINT fk_review_product FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE,
 CONSTRAINT fk_review_store FOREIGN KEY(store_id) REFERENCES stores(id),
 CONSTRAINT chk_review_rating CHECK(rating BETWEEN 1 AND 5),
 INDEX idx_reviews_product(product_id,deleted,id),
 INDEX idx_reviews_seller(store_id,deleted,id)
);
CREATE TABLE product_review_images (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 review_id BIGINT NOT NULL,
 content_type VARCHAR(30) NOT NULL,
 content MEDIUMBLOB NOT NULL,
 sha256 CHAR(64) NOT NULL,
 FOREIGN KEY(review_id) REFERENCES product_reviews(id) ON DELETE CASCADE
);
CREATE TABLE product_review_replies (
 review_id BIGINT PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 text VARCHAR(2000) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(review_id) REFERENCES product_reviews(id) ON DELETE CASCADE,
 FOREIGN KEY(actor_id) REFERENCES user_accounts(id)
);
