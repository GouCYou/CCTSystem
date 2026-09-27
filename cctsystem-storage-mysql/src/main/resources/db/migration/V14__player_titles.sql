CREATE TABLE IF NOT EXISTS cct_player_titles (
    title_id BINARY(16) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    affix_type ENUM('PREFIX', 'SUFFIX') NOT NULL,
    display_value VARCHAR(255) NOT NULL,
    sort_weight INT NOT NULL,
    expires_at TIMESTAMP(3) NULL,
    granted_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (title_id),
    KEY idx_cct_player_titles_owner (player_uuid, affix_type, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_player_title_selection (
    player_uuid BINARY(16) NOT NULL,
    affix_type ENUM('PREFIX', 'SUFFIX') NOT NULL,
    selection_key VARCHAR(96) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_uuid, affix_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
