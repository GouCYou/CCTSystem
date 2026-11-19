CREATE TABLE IF NOT EXISTS cct_chat_filter_incidents (
    incident_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(64) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    original_text TEXT NOT NULL,
    filtered_text TEXT NOT NULL,
    matched_words JSON NOT NULL,
    detected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_chat_filter_time (detected_at),
    KEY idx_chat_filter_server_time (server_id, detected_at),
    KEY idx_chat_filter_player_time (player_uuid, detected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
