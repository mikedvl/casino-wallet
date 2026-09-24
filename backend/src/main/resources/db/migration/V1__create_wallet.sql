CREATE TABLE wallet (
    player_id UUID PRIMARY KEY,
    real_balance NUMERIC(19,2) NOT NULL DEFAULT 0.00,
    bonus_balance NUMERIC(19,2) NOT NULL DEFAULT 0.00,
    CONSTRAINT wallet_real_balance_nonnegative CHECK (real_balance >= 0),
    CONSTRAINT wallet_bonus_balance_nonnegative CHECK (bonus_balance >= 0)
);
