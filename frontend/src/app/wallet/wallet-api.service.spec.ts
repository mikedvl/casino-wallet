import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { WalletApiService } from './wallet-api.service';
import { WalletSummary } from './wallet-summary.model';

describe('WalletApiService', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('gets the wallet from the relative API URL and preserves decimal strings', () => {
    const expected: WalletSummary = { realBalance: '9007199254740993.01', bonusBalance: '0.00' };
    let received: WalletSummary | undefined;

    TestBed.inject(WalletApiService).getWallet().subscribe(wallet => {
      received = wallet;
    });
    const request = http.expectOne('/api/wallet');
    expect(request.request.method).toBe('GET');
    request.flush(expected);

    expect(received).toEqual(expected);
  });
});
