-- CCTSystem foundation schema. Business feature tables are added by later migrations.

CREATE TABLE IF NOT EXISTS cct_players (
    player_uuid BINARY(16) NOT NULL PRIMARY KEY,
    current_name VARCHAR(16) NOT NULL,
    normalized_name VARCHAR(16) NOT NULL,
    first_seen_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_players_normalized_name (normalized_name),
    KEY idx_cct_players_last_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_player_name_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(16) NOT NULL,
    normalized_name VARCHAR(16) NOT NULL,
    first_used_at DATETIME(3) NOT NULL,
    last_used_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_cct_player_name_history (player_uuid, normalized_name),
    KEY idx_cct_player_name_lookup (normalized_name),
    CONSTRAINT fk_cct_player_name_player FOREIGN KEY (player_uuid)
        REFERENCES cct_players(player_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_server_nodes (
    node_id VARCHAR(64) NOT NULL PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    roles_json JSON NOT NULL,
    capabilities_json JSON NOT NULL,
    boot_id CHAR(36) NOT NULL,
    plugin_version VARCHAR(32) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_cct_server_nodes_server (server_id),
    KEY idx_cct_server_nodes_last_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_operation_requests (
    operation_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NULL,
    operation_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_json JSON NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_operation_idempotency (player_uuid, operation_type, idempotency_key),
    KEY idx_cct_operation_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_player_operation_fences (
    player_uuid BINARY(16) NOT NULL PRIMARY KEY,
    operation_id BINARY(16) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    acquired_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_cct_player_fence_updated (state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_point_operations (
    operation_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    delta_points INT NOT NULL,
    balance_before INT NULL,
    balance_after INT NULL,
    status VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(80) NOT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_cct_point_operations_player (player_uuid, created_at),
    KEY idx_cct_point_operations_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_outbox_jobs (
    job_id BINARY(16) NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    job_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_outbox_aggregate (aggregate_type, aggregate_id, aggregate_version, job_type),
    KEY idx_cct_outbox_delivery (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_audit_logs (
    audit_id BINARY(16) NOT NULL PRIMARY KEY,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(80) NOT NULL,
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(80) NOT NULL,
    request_id VARCHAR(80) NULL,
    transaction_id VARCHAR(80) NULL,
    origin VARCHAR(32) NOT NULL,
    metadata_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_cct_audit_target (target_type, target_id, created_at),
    KEY idx_cct_audit_actor (actor_type, actor_id, created_at),
    KEY idx_cct_audit_transaction (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
