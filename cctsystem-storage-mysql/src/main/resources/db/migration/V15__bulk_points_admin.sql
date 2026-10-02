CREATE TABLE IF NOT EXISTS cct_admin_points_batches (
    batch_id BINARY(16) NOT NULL PRIMARY KEY,
    server_id VARCHAR(64) NOT NULL,
    points INT NOT NULL,
    scope VARCHAR(16) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS cct_admin_points_recipients (
    batch_id BINARY(16) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    error_code VARCHAR(80) NULL,
    PRIMARY KEY (batch_id, player_uuid),
    KEY idx_admin_points_pending (status, batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
