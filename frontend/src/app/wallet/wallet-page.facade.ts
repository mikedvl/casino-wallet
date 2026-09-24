import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslationKey } from '../i18n/translations';
import { WalletApiService } from './wallet-api.service';
import { DepositResponse, LedgerPage, RoundResponse, WalletSummary } from './wallet-summary.model';

const errorKeys: Readonly<Record<string, TranslationKey | undefined>> = {
  INSUFFICIENT_FUNDS: 'errorInsufficientFunds',
  MAX_BET_EXCEEDED: 'errorMaxBet',
  WALLET_BALANCE_LIMIT: 'errorBalanceLimit',
  INVALID_DEPOSIT_AMOUNT: 'errorDepositAmount',
  INVALID_ROUND_AMOUNT: 'errorRoundAmount',
  DEPOSIT_NOT_FOUND: 'errorDepositNotFound',
  DEPOSIT_AMOUNT_MISMATCH: 'errorAmountMismatch',
  INVALID_REQUEST: 'errorInvalidRequest',
  INVALID_CALLBACK: 'errorInvalidRequest',
  INVALID_SIGNATURE: 'errorSignature',
  INVALID_PAGINATION: 'errorPagination',
};

function errorKey(error: unknown): TranslationKey {
  const body: unknown = error instanceof HttpErrorResponse ? error.error : null;
  if (typeof body === 'object' && body !== null && 'code' in body && typeof body.code === 'string') {
    return Object.hasOwn(errorKeys, body.code) ? errorKeys[body.code] ?? 'errorGeneric' : 'errorGeneric';
  }
  return 'errorGeneric';
}

@Injectable()
export class WalletPageFacade {
  private readonly api = inject(WalletApiService);
  private readonly destroyRef = inject(DestroyRef);
  private generation = 0;

  readonly wallet = signal<WalletSummary | null>(null);
  readonly ledgerPage = signal<LedgerPage | null>(null);
  readonly loadingWallet = signal(false);
  readonly loadingLedger = signal(false);
  readonly walletError = signal(false);
  readonly ledgerError = signal(false);
  readonly depositBusy = signal(false);
  readonly roundBusy = signal(false);
  readonly busy = computed(() => this.depositBusy() || this.roundBusy());
  readonly deposit = signal<DepositResponse | null>(null);
  readonly roundResult = signal<RoundResponse | null>(null);
  readonly depositError = signal<TranslationKey | null>(null);
  readonly roundError = signal<TranslationKey | null>(null);

  refresh(): void {
    if (this.busy()) return;
    const generation = ++this.generation;
    this.loadingWallet.set(true);
    this.loadingLedger.set(true);
    this.walletError.set(false);
    this.ledgerError.set(false);
    this.api.getWallet().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: wallet => {
        if (generation !== this.generation) return;
        this.wallet.set(wallet);
        this.loadingWallet.set(false);
        // Wallet reads can resolve lifecycle. Read the ledger only after that transaction commits.
        this.loadLedger(0, generation);
      },
      error: () => {
        if (generation !== this.generation) return;
        this.walletError.set(true);
        this.loadingWallet.set(false);
        this.loadingLedger.set(false);
      },
    });
  }

  goToPage(page: number): void {
    const ledger = this.ledgerPage();
    if (this.busy() || this.loadingWallet() || this.loadingLedger() || this.walletError()
      || !ledger || page < 0 || page >= ledger.totalPages) return;
    this.loadLedger(page, ++this.generation);
  }

  createDeposit(amount: string): void {
    if (this.busy()) return;
    this.beginMutation();
    this.depositBusy.set(true);
    this.depositError.set(null);
    this.deposit.set(null);
    this.api.createDeposit(amount).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: deposit => {
        this.deposit.set(deposit);
        this.depositBusy.set(false);
        // A pending deposit has no wallet/lifecycle side effects, including no incidental wallet refresh.
      },
      error: error => {
        this.depositError.set(errorKey(error));
        this.depositBusy.set(false);
      },
    });
  }

  completeDeposit(): void {
    const deposit = this.deposit();
    if (this.busy() || !deposit || deposit.status !== 'PENDING') return;
    this.beginMutation();
    this.depositBusy.set(true);
    this.depositError.set(null);
    this.api.completeDeposit(deposit.depositId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: completion => {
        this.deposit.set({ ...deposit, status: completion.status });
        this.depositBusy.set(false);
        this.refresh();
      },
      error: error => {
        this.depositError.set(errorKey(error));
        this.depositBusy.set(false);
        this.refresh();
      },
    });
  }

  playRound(stake: string, totalWin: string): void {
    if (this.busy()) return;
    this.beginMutation();
    this.roundBusy.set(true);
    this.roundResult.set(null);
    this.roundError.set(null);
    this.api.playRound(stake, totalWin).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: result => {
        this.roundResult.set(result);
        this.roundBusy.set(false);
        this.refresh();
      },
      error: error => {
        this.roundError.set(errorKey(error));
        this.roundBusy.set(false);
        // A business rejection can still commit prior expiration; never retain a stale bonus balance.
        this.refresh();
      },
    });
  }

  private beginMutation(): void {
    // Invalidate any older reads even when the newer operation is rejected.
    ++this.generation;
    this.loadingWallet.set(false);
    this.loadingLedger.set(false);
  }

  private loadLedger(page: number, generation: number): void {
    this.loadingLedger.set(true);
    this.ledgerError.set(false);
    this.api.getLedger(page).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ledger => {
        if (generation !== this.generation) return;
        this.ledgerPage.set(ledger);
        this.loadingLedger.set(false);
      },
      error: () => {
        if (generation !== this.generation) return;
        this.ledgerError.set(true);
        this.loadingLedger.set(false);
      },
    });
  }
}
