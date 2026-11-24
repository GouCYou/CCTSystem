ALTER TABLE cct_player_mail ADD COLUMN read_at BIGINT NULL;

UPDATE cct_player_mail SET read_at = claimed_at WHERE claimed_at IS NOT NULL;
