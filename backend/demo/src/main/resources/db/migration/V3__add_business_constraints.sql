-- Existing invalid data must be corrected explicitly before applying this migration.
-- No data is silently rewritten or deleted.
ALTER TABLE products
    ADD CONSTRAINT chk_products_price_nonnegative CHECK (price >= 0),
    ADD CONSTRAINT chk_products_stock_nonnegative CHECK (stock >= 0);

ALTER TABLE cart_items
    ADD CONSTRAINT chk_cart_items_quantity_positive CHECK (quantity > 0);
