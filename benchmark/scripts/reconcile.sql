WITH wallet_ledger AS (
    SELECT
        w.id AS wallet_id,
        w.wallet_number,
        w.balance AS actual_balance,
        COALESCE(SUM(
            CASE
                WHEN t.type = 'TOPUP' AND t.target_wallet_id = w.id THEN t.amount
                WHEN t.type = 'WITHDRAW' AND t.source_wallet_id = w.id THEN -t.amount
                WHEN t.type = 'TRANSFER' AND t.target_wallet_id = w.id THEN t.amount
                WHEN t.type = 'TRANSFER' AND t.source_wallet_id = w.id THEN -t.amount
                ELSE 0
            END
        ), 0) AS expected_balance
    FROM wallets w
    LEFT JOIN transactions t
      ON t.source_wallet_id = w.id OR t.target_wallet_id = w.id
    GROUP BY w.id, w.wallet_number, w.balance
)
SELECT
    wallet_id,
    wallet_number,
    actual_balance,
    expected_balance,
    actual_balance - expected_balance AS delta
FROM wallet_ledger
ORDER BY wallet_id;
