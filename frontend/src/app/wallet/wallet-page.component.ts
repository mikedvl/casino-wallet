import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { TranslationService } from '../i18n/translation.service';
import { TranslationKey } from '../i18n/translations';
import { moneyValidator } from './money-validator';
import { WalletPageFacade } from './wallet-page.facade';
import { BonusStatus, OperationType, ReferenceType, WalletType } from './wallet-summary.model';

const bonusLabels: Record<BonusStatus, TranslationKey> = {
  ACTIVE: 'bonusActive', COMPLETED: 'bonusCompleted', EXPIRED: 'bonusExpired',
};
const bonusHints: Record<BonusStatus, TranslationKey> = {
  ACTIVE: 'activeHint', COMPLETED: 'completedHint', EXPIRED: 'expiredHint',
};
const operationLabels: Record<OperationType, TranslationKey> = {
  DEPOSIT_COMPLETED: 'depositCredit', ROUND_STAKE: 'roundStake', ROUND_WIN: 'roundWin',
  WELCOME_BONUS_GRANTED: 'bonusGranted', BONUS_CONVERTED: 'bonusConverted', BONUS_FORFEITED: 'bonusForfeited',
};
const referenceLabels: Record<ReferenceType, TranslationKey> = {
  DEPOSIT: 'depositReference', GAME_ROUND: 'roundReference', BONUS: 'bonusReference',
};
const walletLabels: Record<WalletType, TranslationKey> = { REAL: 'real', BONUS: 'bonus' };

@Component({
  selector: 'app-wallet-page',
  standalone: true,
  imports: [ReactiveFormsModule, DatePipe],
  providers: [WalletPageFacade],
  templateUrl: './wallet-page.component.html',
  styleUrl: './wallet-page.component.css',
})
export class WalletPageComponent implements OnInit {
  readonly facade = inject(WalletPageFacade);
  readonly i18n = inject(TranslationService);
  private readonly forms = inject(FormBuilder).nonNullable;

  readonly depositForm = this.forms.group({ amount: ['', moneyValidator(false)] });
  readonly roundForm = this.forms.group({ stake: ['', moneyValidator(false)], totalWin: ['0.00', moneyValidator(true)] });
  readonly actionsDisabled = computed(() => this.facade.busy() || this.facade.loadingWallet()
    || this.facade.loadingLedger() || this.facade.walletError());

  ngOnInit(): void { this.facade.refresh(); }
  t(key: TranslationKey): string { return this.i18n.t(key); }
  bonusLabel(status: BonusStatus): string { return this.t(bonusLabels[status] ?? 'unknownValue'); }
  bonusHint(status: BonusStatus): string { return this.t(bonusHints[status] ?? 'unknownValue'); }
  operationLabel(operation: OperationType): string { return this.t(operationLabels[operation] ?? 'unknownValue'); }
  referenceLabel(reference: ReferenceType): string { return this.t(referenceLabels[reference] ?? 'unknownValue'); }
  walletLabel(wallet: WalletType): string { return this.t(walletLabels[wallet] ?? 'unknownValue'); }

  createDeposit(): void {
    this.depositForm.markAllAsTouched();
    if (this.depositForm.valid && !this.actionsDisabled()) {
      this.facade.createDeposit(this.depositForm.getRawValue().amount);
    }
  }

  playRound(): void {
    this.roundForm.markAllAsTouched();
    if (this.roundForm.valid && !this.actionsDisabled()) {
      const { stake, totalWin } = this.roundForm.getRawValue();
      this.facade.playRound(stake, totalWin);
    }
  }
}
