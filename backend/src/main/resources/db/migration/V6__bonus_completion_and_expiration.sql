ALTER TABLE bonus
    DROP CONSTRAINT bonus_status_valid,
    ADD CONSTRAINT bonus_status_valid CHECK (status IN ('ACTIVE', 'COMPLETED', 'EXPIRED'));

CREATE FUNCTION protect_terminal_bonus_status() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'ACTIVE' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'Terminal bonus status cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bonus_terminal_status
    BEFORE UPDATE OF status ON bonus
    FOR EACH ROW EXECUTE FUNCTION protect_terminal_bonus_status();

ALTER TABLE ledger_entry
    DROP CONSTRAINT ledger_entry_operation_type_check,
    DROP CONSTRAINT ledger_entry_reference_type_check,
    DROP CONSTRAINT ledger_entry_operation_sign_reference_check,
    ADD CONSTRAINT ledger_entry_operation_type_check CHECK (
        operation_type IN ('DEPOSIT_COMPLETED', 'WELCOME_BONUS_GRANTED', 'ROUND_STAKE', 'ROUND_WIN',
                           'BONUS_CONVERTED', 'BONUS_FORFEITED')
    ),
    ADD CONSTRAINT ledger_entry_reference_type_check CHECK (reference_type IN ('DEPOSIT', 'GAME_ROUND', 'BONUS')),
    ADD CONSTRAINT ledger_entry_operation_sign_reference_check CHECK (
        (operation_type = 'DEPOSIT_COMPLETED' AND wallet_type = 'REAL' AND reference_type = 'DEPOSIT' AND amount > 0)
        OR (operation_type = 'WELCOME_BONUS_GRANTED' AND wallet_type = 'BONUS' AND reference_type = 'DEPOSIT' AND amount > 0)
        OR (operation_type = 'ROUND_STAKE' AND reference_type = 'GAME_ROUND' AND amount < 0)
        OR (operation_type = 'ROUND_WIN' AND reference_type = 'GAME_ROUND' AND amount > 0)
        OR (operation_type = 'BONUS_CONVERTED' AND reference_type = 'BONUS'
            AND ((wallet_type = 'REAL' AND amount > 0) OR (wallet_type = 'BONUS' AND amount < 0)))
        OR (operation_type = 'BONUS_FORFEITED' AND reference_type = 'BONUS' AND wallet_type = 'BONUS' AND amount < 0)
    );
