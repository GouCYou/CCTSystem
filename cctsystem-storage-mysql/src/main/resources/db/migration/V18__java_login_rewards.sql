CREATE TABLE IF NOT EXISTS cct_java_login_rewards (
    mojang_uuid BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    first_session BINARY(16) NOT NULL,
    auth_registered_at BIGINT NOT NULL,
    reward_server VARCHAR(48) NOT NULL,
    membership_tier VARCHAR(48) NOT NULL,
    membership_days INT NOT NULL,
    points INT NOT NULL,
    commands_json JSON NOT NULL,
    membership_status VARCHAR(16) NOT NULL,
    points_status VARCHAR(16) NOT NULL,
    commands_status VARCHAR(16) NOT NULL,
    ready BOOLEAN GENERATED ALWAYS AS (
        membership_status = 'PENDING' OR points_status = 'PENDING'
        OR (commands_status = 'PENDING' AND membership_status = 'COMPLETED' AND points_status = 'COMPLETED')
    ) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_java_login_player_uuid (player_uuid),
    UNIQUE KEY uq_java_login_player_name (player_name),
    KEY idx_java_login_reward_server (reward_server, ready, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
