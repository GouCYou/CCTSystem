UPDATE cct_membership_entitlements e
JOIN (
    SELECT
        resulting_entitlement_id,
        SUM(discounted_price_points) AS purchase_value_points,
        SUM(duration_days_snapshot * months * 86400) AS purchase_duration_seconds
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
        purchases.purchase_value_points
        * LEAST(
            CASE
                WHEN e.state = 'PAUSED' THEN GREATEST(COALESCE(e.remaining_seconds, 0), 0)
                WHEN e.state = 'ACTIVE' THEN GREATEST(TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), e.expires_at), 0)
                ELSE 0
            END,
            purchases.purchase_duration_seconds
        )
        / NULLIF(purchases.purchase_duration_seconds, 0)
    ),
    e.credit_basis_at = UTC_TIMESTAMP(3)
WHERE e.state IN ('ACTIVE', 'PAUSED');
