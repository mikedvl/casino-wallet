export interface WalletSummary {
  readonly realBalance: string;
  readonly bonusBalance: string;
  readonly bonus: BonusSummary | null;
}

export type BonusStatus = 'ACTIVE' | 'COMPLETED' | 'EXPIRED';

export interface BonusSummary {
  readonly status: BonusStatus;
  readonly initialAmount: string;
  readonly wageringProgress: string;
  readonly wageringTarget: string;
  readonly expiresAt: string;
}

export interface DepositResponse {
  readonly depositId: string;
  readonly amount: string;
  readonly status: 'PENDING' | 'COMPLETED';
}

export interface DepositCompletion {
  readonly depositId: string;
  readonly status: 'COMPLETED';
}

export interface RoundResponse {
  readonly roundId: string;
  readonly stake: string;
  readonly totalWin: string;
  readonly realBalance: string;
  readonly bonusBalance: string;
}

export type WalletType = 'REAL' | 'BONUS';
export type OperationType = 'DEPOSIT_COMPLETED' | 'ROUND_STAKE' | 'ROUND_WIN' | 'WELCOME_BONUS_GRANTED'
  | 'BONUS_CONVERTED' | 'BONUS_FORFEITED';
export type ReferenceType = 'DEPOSIT' | 'GAME_ROUND' | 'BONUS';

export interface LedgerEntry {
  readonly id: string;
  readonly walletType: WalletType;
  readonly operationType: OperationType;
  readonly amount: string;
  readonly balanceAfter: string;
  readonly referenceType: ReferenceType;
  readonly referenceId: string;
  readonly createdAt: string;
}

export interface LedgerPage {
  readonly items: readonly LedgerEntry[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}
