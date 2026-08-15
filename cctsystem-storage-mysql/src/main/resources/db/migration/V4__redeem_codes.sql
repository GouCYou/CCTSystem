CREATE TABLE IF NOT EXISTS cct_redeem_batches (
    batch_id BINARY(16) NOT NULL PRIMARY KEY,
    code_count INT NOT NULL,
    code_length INT NOT NULL,
    max_uses_per_code INT NOT NULL,
    valid_from DATETIME(3) NOT NULL,
    valid_until DATETIME(3) NULL,
    creator VARCHAR(80) NOT NULL,
    note VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_cct_redeem_batch_values CHECK (
        code_count BETWEEN 1 AND 10000 AND code_length BETWEEN 8 AND 32
        AND max_uses_per_code > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_redeem_codes (
    code_id BINARY(16) NOT NULL PRIMARY KEY,
    batch_id BINARY(16) NOT NULL,
    code_hash BINARY(32) NOT NULL,
    max_uses INT NOT NULL,
    used_uses INT NOT NULL DEFAULT 0,
    valid_from DATETIME(3) NOT NULL,
    valid_until DATETIME(3) NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    note VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_redeem_code_hash (code_hash),
    KEY idx_cct_redeem_code_batch (batch_id),
    KEY idx_cct_redeem_code_validity (state, valid_from, valid_until),
    CONSTRAINT fk_cct_redeem_code_batch FOREIGN KEY (batch_id)
        REFERENCES cct_redeem_batches(batch_id) ON DELETE CASCADE,
    CONSTRAINT chk_cct_redeem_code_uses CHECK (used_uses >= 0 AND used_uses <= max_uses)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_redeem_rewards (
    reward_id BINARY(16) NOT NULL PRIMARY KEY,
    code_id BINARY(16) NOT NULL,
    ordinal INT NOT NULL,
    reward_type VARCHAR(32) NOT NULL,
    payload_version INT NOT NULL DEFAULT 1,
    payload_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_redeem_reward_ordinal (code_id, ordinal),
    CONSTRAINT fk_cct_redeem_reward_code FOREIGN KEY (code_id)
        REFERENCES cct_redeem_codes(code_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_redeem_uses (
    use_id BINARY(16) NOT NULL PRIMARY KEY,
    code_id BINARY(16) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    owner_node_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    origin VARCHAR(24) NOT NULL,
    state VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    response_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_redeem_code_player (code_id, player_uuid),
    UNIQUE KEY uk_cct_redeem_use_idempotency (player_uuid, idempotency_key),
    KEY idx_cct_redeem_use_player (player_uuid, created_at),
    KEY idx_cct_redeem_use_owner (owner_node_id, state, updated_at),
    KEY idx_cct_redeem_use_state (state, updated_at),
    CONSTRAINT fk_cct_redeem_use_code FOREIGN KEY (code_id)
        REFERENCES cct_redeem_codes(code_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_reward_deliveries (
    delivery_id BINARY(16) NOT NULL PRIMARY KEY,
    use_id BINARY(16) NOT NULL,
    reward_id BINARY(16) NOT NULL,
    reward_type VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    external_ref VARCHAR(80) NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_reward_delivery (use_id, reward_id),
    KEY idx_cct_reward_delivery_state (state, updated_at),
    CONSTRAINT fk_cct_reward_delivery_use FOREIGN KEY (use_id)
        REFERENCES cct_redeem_uses(use_id) ON DELETE CASCADE,
    CONSTRAINT fk_cct_reward_delivery_reward FOREIGN KEY (reward_id)
        REFERENCES cct_redeem_rewards(reward_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
