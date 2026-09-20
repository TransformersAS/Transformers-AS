-- CANCELLATION_REQUESTED requires 22 characters; preserve all existing status values.
ALTER TABLE orders MODIFY COLUMN status VARCHAR(32) NOT NULL;
