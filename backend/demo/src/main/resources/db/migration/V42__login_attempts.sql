-- CU-08 H02: independent of account existence; epoch milliseconds use the shared DB clock.
CREATE TABLE login_attempts (
    attempt_key BINARY(32) NOT NULL PRIMARY KEY,
    failure_1 BIGINT NULL,
    failure_2 BIGINT NULL,
    failure_3 BIGINT NULL,
    failure_4 BIGINT NULL,
    failure_5 BIGINT NULL,
    blocked_until BIGINT NULL
) ENGINE=InnoDB;
