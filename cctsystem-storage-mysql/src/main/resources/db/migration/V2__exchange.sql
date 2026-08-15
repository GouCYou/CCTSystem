CREATE TABLE IF NOT EXISTS cct_exchange_cap_groups (
    cap_group VARCHAR(64) NOT NULL PRIMARY KEY,
    weekly_limit_points INT NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_exchange_sources (
    source_id VARCHAR(64) NOT NULL PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    currency_display_name VARCHAR(32) NOT NULL,
    currency_units_per_point DECIMAL(20,4) NOT NULL,
    cap_group VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_cct_exchange_source_server (server_id, enabled),
    CONSTRAINT fk_cct_exchange_source_cap FOREIGN KEY (cap_group)
        REFERENCES cct_exchange_cap_groups(cap_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_exchange_weekly_usage (
    player_uuid BINARY(16) NOT NULL,
    week_start DATE NOT NULL,
    cap_group VARCHAR(64) NOT NULL,
    completed_points INT NOT NULL DEFAULT 0,
    reserved_points INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_uuid, week_start, cap_group),
    KEY idx_cct_exchange_usage_week (week_start, cap_group),
    CONSTRAINT fk_cct_exchange_usage_cap FOREIGN KEY (cap_group)
        REFERENCES cct_exchange_cap_groups(cap_group),
    CONSTRAINT chk_cct_exchange_usage_nonnegative CHECK (
        completed_points >= 0 AND reserved_points >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_exchange_transactions (
    transaction_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    cap_group VARCHAR(64) NOT NULL,
    week_start DATE NOT NULL,
    requested_points INT NOT NULL,
    currency_units_per_point DECIMAL(20,4) NOT NULL,
    currency_cost DECIMAL(20,4) NOT NULL,
    origin VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    status VARCHAR(40) NOT NULL,
    currency_balance_before DECIMAL(20,4) NULL,
    currency_balance_after DECIMAL(20,4) NULL,
    points_balance_before INT NULL,
    points_balance_after INT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_exchange_idempotency (player_uuid, idempotency_key),
    KEY idx_cct_exchange_player_time (player_uuid, created_at),
    KEY idx_cct_exchange_status (status, updated_at),
    KEY idx_cct_exchange_node_status (node_id, status, updated_at),
    CONSTRAINT fk_cct_exchange_transaction_source FOREIGN KEY (source_id)
        REFERENCES cct_exchange_sources(source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_exchange_actions (
    action_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    transaction_id BINARY(16) NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    amount DECIMAL(20,4) NULL,
    points INT NULL,
    balance_before DECIMAL(20,4) NULL,
    balance_after DECIMAL(20,4) NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_cct_exchange_action_transaction (transaction_id, action_id),
    CONSTRAINT fk_cct_exchange_action_transaction FOREIGN KEY (transaction_id)
        REFERENCES cct_exchange_transactions(transaction_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
