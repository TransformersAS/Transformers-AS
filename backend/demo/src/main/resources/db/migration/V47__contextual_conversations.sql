-- CU10 capability only: an active account may read while sending is restricted.
-- Does not change account deactivation, moderation decisions or sanction workflows.
ALTER TABLE user_accounts ADD COLUMN communications_restricted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE conversations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 buyer_id BIGINT NOT NULL, seller_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL, order_id BIGINT NULL,
 context_order BIGINT GENERATED ALWAYS AS (COALESCE(order_id,0)) STORED,
 closed BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT uq_conversation_context UNIQUE(buyer_id,seller_id,product_id,context_order),
 FOREIGN KEY(buyer_id) REFERENCES user_accounts(id),
 FOREIGN KEY(seller_id) REFERENCES user_accounts(id),
 FOREIGN KEY(store_id) REFERENCES stores(id),
 FOREIGN KEY(product_id) REFERENCES products(id),
 FOREIGN KEY(order_id) REFERENCES orders(id),
 CHECK(buyer_id <> seller_id),
 INDEX idx_conversation_buyer(buyer_id,updated_at,id),
 INDEX idx_conversation_seller(seller_id,updated_at,id)
);
CREATE TABLE conversation_participants (
 conversation_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
 archived BOOLEAN NOT NULL DEFAULT FALSE, read_through BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(conversation_id,account_id),
 FOREIGN KEY(conversation_id) REFERENCES conversations(id),
 FOREIGN KEY(account_id) REFERENCES user_accounts(id)
);
CREATE TABLE conversation_messages (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT NOT NULL,
 sender_id BIGINT NOT NULL, client_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 text VARCHAR(4000) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(conversation_id,sender_id) REFERENCES conversation_participants(conversation_id,account_id),
 UNIQUE(conversation_id,sender_id,client_key),
 INDEX idx_message_history(conversation_id,id)
);
CREATE TABLE conversation_images (
 message_id BIGINT PRIMARY KEY, content_type VARCHAR(20) NOT NULL,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content MEDIUMBLOB NOT NULL,
 FOREIGN KEY(message_id) REFERENCES conversation_messages(id),
 CHECK(content_type IN ('image/jpeg','image/png','image/webp')),
 CHECK(OCTET_LENGTH(content) BETWEEN 1 AND 5242880)
);
