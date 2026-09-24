CREATE TABLE game_round (
    id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES wallet(player_id),
    stake NUMERIC(19,2) NOT NULL,
    total_win NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT game_round_stake_positive CHECK (stake > 0 AND stake <= 99999999999999999.99),
    CONSTRAINT game_round_win_nonnegative CHECK (total_win >= 0 AND total_win <= 99999999999999999.99)
);

ALTER TABLE ledger_entry
    DROP CONSTRAINT ledger_entry_operation_type_check,
    DROP CONSTRAINT ledger_entry_reference_type_check,
    DROP CONSTRAINT ledger_entry_amount_check,
    ADD CONSTRAINT ledger_entry_operation_type_check
        CHECK (operation_type IN ('DEPOSIT_COMPLETED', 'ROUND_STAKE', 'ROUND_WIN')),
    ADD CONSTRAINT ledger_entry_reference_type_check
        CHECK (reference_type IN ('DEPOSIT', 'GAME_ROUND')),
    ADD CONSTRAINT ledger_entry_amount_check
        CHECK (amount <> 0 AND amount BETWEEN -99999999999999999.99 AND 99999999999999999.99),
    ADD CONSTRAINT ledger_entry_operation_sign_reference_check CHECK (
        (operation_type = 'DEPOSIT_COMPLETED' AND reference_type = 'DEPOSIT' AND amount > 0)
        OR (operation_type = 'ROUND_STAKE' AND reference_type = 'GAME_ROUND' AND amount < 0)
        OR (operation_type = 'ROUND_WIN' AND reference_type = 'GAME_ROUND' AND amount > 0)
    );
