import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { WalletApiService } from './wallet-api.service';
import { WalletSummary } from './wallet-summary.model';

@Component({
  selector: 'app-wallet-page',
  standalone: true,
  template: `
    <section aria-label="Wallet balances">
      @if (wallet(); as wallet) {
        <dl class="balances">
          <div>
            <dt>Real balance</dt>
            <dd>{{ wallet.realBalance }} EUR</dd>
          </div>
          <div>
            <dt>Bonus balance</dt>
            <dd>{{ wallet.bonusBalance }} EUR</dd>
          </div>
        </dl>
      } @else if (hasError()) {
        <p role="alert">Unable to load wallet. Please try again.</p>
        <button type="button" (click)="loadWallet()">Retry</button>
      } @else {
        <p role="status">Loading wallet...</p>
      }
    </section>
  `,
  styleUrl: './wallet-page.component.css',
})
export class WalletPageComponent implements OnInit {
  private readonly walletApi = inject(WalletApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly wallet = signal<WalletSummary | null>(null);
  readonly hasError = signal(false);

  ngOnInit(): void {
    this.loadWallet();
  }

  loadWallet(): void {
    this.wallet.set(null);
    this.hasError.set(false);
    this.walletApi.getWallet().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: wallet => this.wallet.set(wallet),
      error: () => this.hasError.set(true),
    });
  }
}
