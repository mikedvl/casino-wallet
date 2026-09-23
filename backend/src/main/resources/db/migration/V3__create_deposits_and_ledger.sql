CREATE TABLE deposit (
    id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES wallet (player_id),
    amount NUMERIC(19,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT deposit_amount_positive CHECK (amount > 0 AND amount <= 99999999999999999.99),
    CONSTRAINT deposit_status_valid CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT deposit_completion_consistent CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL)
    )
);

CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES wallet (player_id),
    wallet_type VARCHAR(16) NOT NULL CHECK (wallet_type = 'REAL'),
    operation_type VARCHAR(32) NOT NULL CHECK (operation_type = 'DEPOSIT_COMPLETED'),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0 AND amount <= 99999999999999999.99),
    balance_after NUMERIC(19,2) NOT NULL CHECK (balance_after >= 0 AND balance_after <= 99999999999999999.99),
    reference_type VARCHAR(16) NOT NULL CHECK (reference_type = 'DEPOSIT'),
    reference_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ledger_entry_business_operation_unique
        UNIQUE (player_id, reference_type, reference_id, operation_type, wallet_type)
);

CREATE INDEX ledger_entry_player_history_idx ON ledger_entry (player_id, created_at DESC, id DESC);

CREATE FUNCTION reject_ledger_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER ledger_entry_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
