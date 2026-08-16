ALTER TABLE cct_membership_entitlements
    ADD COLUMN credit_basis_points INT NOT NULL DEFAULT 0 AFTER resume_sequence,
    ADD COLUMN credit_basis_seconds BIGINT NOT NULL DEFAULT 0 AFTER credit_basis_points,
    ADD COLUMN credit_basis_at DATETIME(3) NULL AFTER credit_basis_seconds;

UPDATE cct_membership_entitlements e
JOIN (
    SELECT
        resulting_entitlement_id,
        SUM(final_price_points) AS paid_points,
        SUM(duration_days_snapshot * months * 86400) AS paid_seconds
    FROM cct_membership_orders
    WHERE status = 'COMPLETED' AND resulting_entitlement_id IS NOT NULL
    GROUP BY resulting_entitlement_id
) purchases ON purchases.resulting_entitlement_id = e.entitlement_id
SET
    e.credit_basis_seconds = CASE
        WHEN e.state = 'PAUSED' THEN GREATEST(COALESCE(e.remaining_seconds, 0), 0)
        WHEN e.state = 'ACTIVE' THEN GREATEST(TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), e.expires_at), 0)
        ELSE 0
    END,
    e.credit_basis_points = FLOOR(
        purchases.paid_points
        * LEAST(
            CASE
                WHEN e.state = 'PAUSED' THEN GREATEST(COALESCE(e.remaining_seconds, 0), 0)
                WHEN e.state = 'ACTIVE' THEN GREATEST(TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), e.expires_at), 0)
                ELSE 0
            END,
            purchases.paid_seconds
        )
        / NULLIF(purchases.paid_seconds, 0)
    ),
    e.credit_basis_at = UTC_TIMESTAMP(3)
WHERE e.state IN ('ACTIVE', 'PAUSED');

ALTER TABLE cct_membership_entitlements
    ADD CONSTRAINT chk_cct_membership_credit_basis CHECK (
        credit_basis_points >= 0 AND credit_basis_seconds >= 0
    );
