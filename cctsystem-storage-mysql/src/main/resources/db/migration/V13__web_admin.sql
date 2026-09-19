CREATE TABLE IF NOT EXISTS cct_redstone_incidents (
    incident_id BINARY(16) NOT NULL PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    world_name VARCHAR(128) NOT NULL,
    block_x INT NOT NULL,
    block_y INT NOT NULL,
    block_z INT NOT NULL,
    nearby_players TEXT NOT NULL,
    detected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_cct_redstone_incidents_time (detected_at),
    KEY idx_cct_redstone_incidents_server_time (server_id, detected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_admin_audit (
    audit_id BINARY(16) NOT NULL PRIMARY KEY,
    actor_uuid BINARY(16) NOT NULL,
    actor_name VARCHAR(64) NOT NULL,
    actor_group VARCHAR(32) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    target_ref VARCHAR(255) NOT NULL,
    detail_json JSON NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_cct_admin_audit_actor_time (actor_uuid, created_at),
    KEY idx_cct_admin_audit_action_time (action_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
