CREATE TABLE IF NOT EXISTS cct_player_mail (
    mail_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    title VARCHAR(96) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    rewards JSON NOT NULL,
    sender VARCHAR(96) NOT NULL,
    expires_at BIGINT NULL,
    claimed_at BIGINT NULL,
    created_at BIGINT NOT NULL,
    automatic BOOLEAN NOT NULL DEFAULT FALSE,
    notified BOOLEAN NOT NULL DEFAULT FALSE,
    KEY idx_cct_player_mail_inbox (player_uuid, created_at, mail_id),
    KEY idx_cct_player_mail_created (created_at, mail_id),
    KEY idx_cct_player_mail_notices (automatic, notified, player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_mail_attachments (
    attachment_id BINARY(16) NOT NULL PRIMARY KEY,
    mail_id BINARY(16) NOT NULL,
    reward_type VARCHAR(24) NOT NULL,
    amount INT UNSIGNED NOT NULL,
    membership_tier VARCHAR(48) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_cct_mail_attachments_mail
        FOREIGN KEY (mail_id) REFERENCES cct_player_mail(mail_id) ON DELETE CASCADE,
    KEY idx_cct_mail_attachment_dispatch (status, reward_type, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cct_lag_compensation (
    config_id INT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NULL,
    rewards JSON NULL,
    incident_id BINARY(16) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO cct_lag_compensation(config_id) VALUES (1);
