-- C06-B: transactional outbox storage. Publisher/worker behavior is implemented in later tickets.
CREATE TABLE outbox_events (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    claim_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    CONSTRAINT uq_outbox_aggregate_event UNIQUE (aggregate_type, aggregate_id, event_type),
    CONSTRAINT chk_outbox_aggregate CHECK (aggregate_type = 'PAYMENT' AND aggregate_id > 0),
    CONSTRAINT chk_outbox_event CHECK (
        event_type = 'payment.completed.v1' AND schema_version = 1
        AND JSON_TYPE(payload) = 'OBJECT'
    ),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','CLAIMED','DONE','DEAD')),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0 AND attempts <= 5),
    CONSTRAINT chk_outbox_claim CHECK (
        (status = 'CLAIMED' AND lease_until IS NOT NULL AND claim_token IS NOT NULL
            AND claim_owner IS NOT NULL AND processed_at IS NULL)
        OR (status <> 'CLAIMED' AND lease_until IS NULL AND claim_token IS NULL AND claim_owner IS NULL)
    ),
    CONSTRAINT chk_outbox_processed CHECK (
        (status IN ('PENDING','CLAIMED') AND processed_at IS NULL)
        OR (status IN ('DONE','DEAD') AND processed_at IS NOT NULL)
    ),
    CONSTRAINT chk_outbox_dead_error CHECK (status <> 'DEAD' OR last_error IS NOT NULL),
    INDEX idx_outbox_dispatch (status, next_attempt_at, created_at),
    INDEX idx_outbox_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
