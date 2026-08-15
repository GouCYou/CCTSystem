CREATE TABLE IF NOT EXISTS cct_delivery_reward_claims (
    player_uuid BINARY(16) NOT NULL,
    reward_key VARCHAR(48) NOT NULL,
    state VARCHAR(24) NOT NULL,
    transaction_id BINARY(16) NULL,
    next_available_at DATETIME(3) NOT NULL,
    reserved_at DATETIME(3) NULL,
    last_claimed_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_uuid, reward_key),
    KEY idx_cct_delivery_reward_ready (state, next_available_at),
    KEY idx_cct_delivery_reward_transaction (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_delivery_reward_audit (
    audit_id BINARY(16) NOT NULL PRIMARY KEY,
    transaction_id BINARY(16) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    reward_key VARCHAR(48) NOT NULL,
    action VARCHAR(24) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    detail VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_cct_delivery_reward_audit_player (player_uuid, created_at),
    KEY idx_cct_delivery_reward_audit_transaction (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
