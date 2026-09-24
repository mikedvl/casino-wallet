import { Component } from '@angular/core';
import { WalletPageComponent } from './wallet/wallet-page.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [WalletPageComponent],
  template: '<app-wallet-page />',
})
export class AppComponent {}
