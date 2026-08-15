CREATE TABLE IF NOT EXISTS cct_nickname_profiles (
    player_uuid BINARY(16) NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    nickname VARCHAR(16) NULL,
    display_rank VARCHAR(24) NULL,
    skin_name VARCHAR(16) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_nickname_active_name (nickname),
    CONSTRAINT chk_cct_nickname_enabled CHECK (
        (enabled = FALSE AND nickname IS NULL)
        OR (enabled = TRUE AND nickname IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_nickname_audit (
    audit_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    action VARCHAR(24) NOT NULL,
    nickname VARCHAR(16) NULL,
    display_rank VARCHAR(24) NULL,
    skin_name VARCHAR(16) NULL,
    server_id VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_cct_nickname_audit_player (player_uuid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
