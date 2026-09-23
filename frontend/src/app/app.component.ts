import { Component } from '@angular/core';
import { WalletPageComponent } from './wallet/wallet-page.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [WalletPageComponent],
  template: `
    <main>
      <h1>Casino Wallet</h1>
      <app-wallet-page />
    </main>
  `,
})
export class AppComponent {}
