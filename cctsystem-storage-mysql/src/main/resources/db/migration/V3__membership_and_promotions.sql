CREATE TABLE IF NOT EXISTS cct_membership_tiers (
    tier_key VARCHAR(64) NOT NULL PRIMARY KEY,
    display_name VARCHAR(24) NOT NULL,
    priority INT NOT NULL,
    luckperms_group VARCHAR(64) NOT NULL,
    duration_days INT NOT NULL,
    price_points INT NOT NULL,
    upgrade_credit_rate_bps INT NOT NULL,
    display_material VARCHAR(64) NOT NULL,
    benefits_json JSON NOT NULL,
    enabled BOOLEAN NOT NULL,
    config_version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_membership_tier_priority (priority),
    CONSTRAINT chk_cct_membership_tier_values CHECK (
        priority > 0 AND duration_days > 0 AND price_points >= 0
        AND upgrade_credit_rate_bps BETWEEN 0 AND 10000
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_promotions (
    promotion_id BINARY(16) NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    percent_off_bps INT NOT NULL,
    starts_at DATETIME(3) NOT NULL,
    ends_at DATETIME(3) NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    created_by VARCHAR(80) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    stopped_by VARCHAR(80) NULL,
    stopped_reason VARCHAR(255) NULL,
    stopped_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_cct_promotion_active (status, starts_at, ends_at, priority),
    CONSTRAINT chk_cct_promotion_discount CHECK (percent_off_bps BETWEEN 1 AND 10000),
    CONSTRAINT chk_cct_promotion_time CHECK (ends_at > starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_promotion_targets (
    promotion_id BINARY(16) NOT NULL,
    tier_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (promotion_id, tier_key),
    KEY idx_cct_promotion_target_tier (tier_key, promotion_id),
    CONSTRAINT fk_cct_promotion_target_promotion FOREIGN KEY (promotion_id)
        REFERENCES cct_promotions(promotion_id) ON DELETE CASCADE,
    CONSTRAINT fk_cct_promotion_target_tier FOREIGN KEY (tier_key)
        REFERENCES cct_membership_tiers(tier_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_player_membership_state (
    player_uuid BINARY(16) NOT NULL PRIMARY KEY,
    active_entitlement_id BINARY(16) NULL,
    next_resume_sequence BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_membership_entitlements (
    entitlement_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    tier_key VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    starts_at DATETIME(3) NULL,
    expires_at DATETIME(3) NULL,
    remaining_seconds BIGINT NULL,
    resume_sequence BIGINT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(80) NOT NULL,
    created_by VARCHAR(80) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_cct_membership_entitlement_player (player_uuid, state, created_at),
    KEY idx_cct_membership_expiry (state, expires_at),
    KEY idx_cct_membership_resume (player_uuid, state, resume_sequence),
    CONSTRAINT fk_cct_membership_entitlement_tier FOREIGN KEY (tier_key)
        REFERENCES cct_membership_tiers(tier_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_membership_orders (
    order_id BINARY(16) NOT NULL PRIMARY KEY,
    operation_id BINARY(16) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    tier_key VARCHAR(64) NOT NULL,
    months INT NOT NULL,
    upgrade_mode VARCHAR(24) NOT NULL,
    status VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    tier_version BIGINT NOT NULL,
    duration_days_snapshot INT NOT NULL,
    base_price_points INT NOT NULL,
    promotion_id BINARY(16) NULL,
    promotion_off_bps INT NOT NULL DEFAULT 0,
    discounted_price_points INT NOT NULL,
    upgrade_credit_points INT NOT NULL DEFAULT 0,
    final_price_points INT NOT NULL,
    previous_entitlement_id BINARY(16) NULL,
    resulting_entitlement_id BINARY(16) NULL,
    points_balance_before INT NULL,
    points_balance_after INT NULL,
    error_code VARCHAR(64) NULL,
    response_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cct_membership_order_operation (operation_id),
    UNIQUE KEY uk_cct_membership_order_idempotency (player_uuid, idempotency_key),
    KEY idx_cct_membership_order_player (player_uuid, created_at),
    KEY idx_cct_membership_order_recovery (node_id, status, updated_at),
    CONSTRAINT fk_cct_membership_order_tier FOREIGN KEY (tier_key)
        REFERENCES cct_membership_tiers(tier_key),
    CONSTRAINT fk_cct_membership_order_promotion FOREIGN KEY (promotion_id)
        REFERENCES cct_promotions(promotion_id),
    CONSTRAINT chk_cct_membership_order_values CHECK (
        months > 0 AND base_price_points >= 0 AND promotion_off_bps BETWEEN 0 AND 10000
        AND discounted_price_points >= 0 AND upgrade_credit_points >= 0 AND final_price_points >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_membership_upgrade_credits (
    order_id BINARY(16) NOT NULL PRIMARY KEY,
    previous_entitlement_id BINARY(16) NOT NULL,
    old_tier_key VARCHAR(64) NOT NULL,
    old_price_points INT NOT NULL,
    old_duration_seconds BIGINT NOT NULL,
    remaining_seconds BIGINT NOT NULL,
    credit_rate_bps INT NOT NULL,
    raw_credit_points DECIMAL(20,6) NOT NULL,
    applied_credit_points INT NOT NULL,
    rounding_mode VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_cct_membership_credit_order FOREIGN KEY (order_id)
        REFERENCES cct_membership_orders(order_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_membership_events (
    event_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    entitlement_id BINARY(16) NULL,
    order_id BINARY(16) NULL,
    event_type VARCHAR(48) NOT NULL,
    actor_type VARCHAR(24) NOT NULL,
    actor_id VARCHAR(80) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    before_json JSON NOT NULL,
    after_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_cct_membership_event_player (player_uuid, created_at),
    KEY idx_cct_membership_event_order (order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_luckperms_projections (
    player_uuid BINARY(16) NOT NULL PRIMARY KEY,
    desired_tier_key VARCHAR(64) NULL,
    desired_group VARCHAR(64) NULL,
    desired_expires_at DATETIME(3) NULL,
    desired_version BIGINT NOT NULL,
    applied_version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_cct_luckperms_projection_delivery (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
