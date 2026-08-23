ALTER TABLE cct_player_membership_state
    ADD COLUMN access_paused_entitlement_id BINARY(16) NULL AFTER active_entitlement_id,
    ADD KEY idx_cct_membership_access_pause (access_paused_entitlement_id);
