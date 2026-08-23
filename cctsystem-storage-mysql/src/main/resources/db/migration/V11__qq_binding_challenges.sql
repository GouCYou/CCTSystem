CREATE TABLE IF NOT EXISTS qqbotauth_verification_codes (
    code VARCHAR(12) NOT NULL PRIMARY KEY,
    minecraft_uuid VARCHAR(36) NOT NULL UNIQUE,
    minecraft_name VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    INDEX idx_qqbotauth_verification_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
