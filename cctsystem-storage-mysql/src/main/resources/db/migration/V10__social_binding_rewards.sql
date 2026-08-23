CREATE TABLE IF NOT EXISTS qqbotauth_bindings (
    minecraft_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    minecraft_name VARCHAR(16) NOT NULL,
    group_openid VARCHAR(128) NOT NULL,
    member_openid VARCHAR(128) NOT NULL,
    bound_at BIGINT NOT NULL,
    verification_status VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_qqbotauth_group_member (group_openid, member_openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS qqbotauth_discord_bindings (
    minecraft_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    discord_user_id VARCHAR(32) NOT NULL,
    discord_username VARCHAR(80) NOT NULL,
    bound_at BIGINT NOT NULL,
    UNIQUE KEY uk_qqbotauth_discord_user (discord_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_social_binding_rewards (
    player_uuid BINARY(16) NOT NULL PRIMARY KEY,
    vip_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    coins_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    vip_delivered_at DATETIME(3) NULL,
    coins_delivered_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_cct_social_vip_reward (vip_status, updated_at),
    KEY idx_cct_social_coins_reward (coins_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
