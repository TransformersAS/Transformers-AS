CREATE TABLE addresses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_name VARCHAR(255) NOT NULL,
    street VARCHAR(255) NOT NULL,
    city VARCHAR(255) NOT NULL,
    department VARCHAR(255) NOT NULL,
    postal_code VARCHAR(50),
    phone VARCHAR(50) NOT NULL,

    PRIMARY KEY (id)
);