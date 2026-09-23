import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { WalletSummary } from './wallet-summary.model';

@Injectable({ providedIn: 'root' })
export class WalletApiService {
  private readonly http = inject(HttpClient);

  getWallet(): Observable<WalletSummary> {
    return this.http.get<WalletSummary>('/api/wallet');
  }
}
