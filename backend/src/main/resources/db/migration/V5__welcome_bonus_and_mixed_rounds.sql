CREATE TABLE bonus (
    id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES wallet(player_id),
    source_deposit_id UUID NOT NULL REFERENCES deposit(id),
    initial_amount NUMERIC(19,2) NOT NULL,
    wagering_target NUMERIC(19,2) NOT NULL,
    wagering_progress NUMERIC(19,2) NOT NULL DEFAULT 0.00,
    status VARCHAR(16) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT bonus_player_lifetime_unique UNIQUE (player_id),
    CONSTRAINT bonus_source_deposit_unique UNIQUE (source_deposit_id),
    CONSTRAINT bonus_initial_amount_valid CHECK (initial_amount > 0 AND initial_amount <= 100.00),
    CONSTRAINT bonus_wagering_target_valid CHECK (wagering_target > 0 AND wagering_target = initial_amount * 20),
    CONSTRAINT bonus_wagering_progress_valid CHECK (wagering_progress BETWEEN 0 AND 99999999999999999.99),
    CONSTRAINT bonus_status_valid CHECK (status = 'ACTIVE'),
    CONSTRAINT bonus_lifetime_valid CHECK (expires_at > granted_at)
);

ALTER TABLE game_round
    ADD COLUMN real_stake NUMERIC(19,2),
    ADD COLUMN bonus_stake NUMERIC(19,2),
    ADD COLUMN real_win NUMERIC(19,2),
    ADD COLUMN bonus_win NUMERIC(19,2);

-- Stage 4 settled every historical round entirely in real money.
UPDATE game_round SET real_stake = stake, bonus_stake = 0.00, real_win = total_win, bonus_win = 0.00;

ALTER TABLE game_round
    ALTER COLUMN real_stake SET NOT NULL,
    ALTER COLUMN bonus_stake SET NOT NULL,
    ALTER COLUMN real_win SET NOT NULL,
    ALTER COLUMN bonus_win SET NOT NULL,
    ADD CONSTRAINT game_round_real_stake_valid CHECK (real_stake BETWEEN 0 AND 99999999999999999.99),
    ADD CONSTRAINT game_round_bonus_stake_valid CHECK (bonus_stake BETWEEN 0 AND 99999999999999999.99),
    ADD CONSTRAINT game_round_real_win_valid CHECK (real_win BETWEEN 0 AND 99999999999999999.99),
    ADD CONSTRAINT game_round_bonus_win_valid CHECK (bonus_win BETWEEN 0 AND 99999999999999999.99),
    ADD CONSTRAINT game_round_stake_allocation_valid CHECK (real_stake + bonus_stake = stake),
    ADD CONSTRAINT game_round_win_allocation_valid CHECK (real_win + bonus_win = total_win);

ALTER TABLE ledger_entry
    DROP CONSTRAINT ledger_entry_wallet_type_check,
    DROP CONSTRAINT ledger_entry_operation_type_check,
    DROP CONSTRAINT ledger_entry_operation_sign_reference_check,
    ADD CONSTRAINT ledger_entry_wallet_type_check CHECK (wallet_type IN ('REAL', 'BONUS')),
    ADD CONSTRAINT ledger_entry_operation_type_check
        CHECK (operation_type IN ('DEPOSIT_COMPLETED', 'WELCOME_BONUS_GRANTED', 'ROUND_STAKE', 'ROUND_WIN')),
    ADD CONSTRAINT ledger_entry_operation_sign_reference_check CHECK (
        (operation_type = 'DEPOSIT_COMPLETED' AND wallet_type = 'REAL' AND reference_type = 'DEPOSIT' AND amount > 0)
        OR (operation_type = 'WELCOME_BONUS_GRANTED' AND wallet_type = 'BONUS' AND reference_type = 'DEPOSIT' AND amount > 0)
        OR (operation_type = 'ROUND_STAKE' AND reference_type = 'GAME_ROUND' AND amount < 0)
        OR (operation_type = 'ROUND_WIN' AND reference_type = 'GAME_ROUND' AND amount > 0)
    );
