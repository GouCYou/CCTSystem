ALTER TABLE cct_promotions
 ADD COLUMN discount_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 ADD COLUMN max_uses_per_player INT NULL,
 ADD COLUMN max_players INT NULL,
 ADD COLUMN active_discount_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
   GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN discount_code ELSE NULL END) STORED,
 ADD UNIQUE KEY uk_cct_discount_code (active_discount_code),
 ADD CONSTRAINT chk_cct_discount_personal_limit CHECK (max_uses_per_player IS NULL OR max_uses_per_player=1),
 ADD CONSTRAINT chk_cct_discount_players_limit CHECK (max_players IS NULL OR max_players BETWEEN 1 AND 1000000);
