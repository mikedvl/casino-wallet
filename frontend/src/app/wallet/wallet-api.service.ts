import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DepositCompletion, DepositResponse, LedgerPage, RoundResponse, WalletSummary } from './wallet-summary.model';

@Injectable({ providedIn: 'root' })
export class WalletApiService {
  private readonly http = inject(HttpClient);

  getWallet(): Observable<WalletSummary> {
    return this.http.get<WalletSummary>('/api/wallet');
  }

  createDeposit(amount: string): Observable<DepositResponse> {
    return this.http.post<DepositResponse>('/api/deposits', { amount });
  }

  completeDeposit(depositId: string): Observable<DepositCompletion> {
    return this.http.post<DepositCompletion>(`/api/demo/deposits/${encodeURIComponent(depositId)}/complete`, {});
  }

  playRound(stake: string, totalWin: string): Observable<RoundResponse> {
    return this.http.post<RoundResponse>('/api/rounds/play', { stake, totalWin });
  }

  getLedger(page: number, size = 10): Observable<LedgerPage> {
    return this.http.get<LedgerPage>('/api/ledger', { params: { page, size } });
  }
}
