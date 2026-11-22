-- C04-A: durable idempotency, committed atomically with the payment.
CREATE TABLE idempotency_requests (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id BIGINT NULL,
    reference_code VARCHAR(36) NULL,
    response_version INT NULL,
    response_status INT NULL,
    response_body JSON NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    CONSTRAINT uq_idempotency_actor_operation_key UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT uq_idempotency_transaction UNIQUE (transaction_id),
    CONSTRAINT fk_idempotency_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT fk_idempotency_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    CONSTRAINT chk_idempotency_operation CHECK (operation IN ('TOPUP','WITHDRAW','TRANSFER')),
    CONSTRAINT chk_idempotency_status CHECK (status IN ('PROCESSING','COMPLETED')),
    CONSTRAINT chk_idempotency_snapshot_type CHECK (response_body IS NULL OR JSON_TYPE(response_body)='OBJECT'),
    CONSTRAINT chk_idempotency_result CHECK (
        (status='PROCESSING' AND transaction_id IS NULL AND reference_code IS NULL
            AND response_version IS NULL AND response_status IS NULL AND response_body IS NULL AND completed_at IS NULL)
        OR (status='COMPLETED' AND transaction_id IS NOT NULL AND reference_code IS NOT NULL
            AND response_version IS NOT NULL AND response_version=1
            AND response_status IS NOT NULL AND response_status=200
            AND response_body IS NOT NULL AND completed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
