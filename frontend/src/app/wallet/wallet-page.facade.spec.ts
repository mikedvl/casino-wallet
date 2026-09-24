import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { WalletPageFacade } from './wallet-page.facade';

describe('WalletPageFacade', () => {
  let facade: WalletPageFacade;
  let http: HttpTestingController;
  const emptyLedger = { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
  const wallet = { realBalance: '20.00', bonusBalance: '20.00', bonus: null };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), WalletPageFacade] });
    http = TestBed.inject(HttpTestingController);
    facade = TestBed.inject(WalletPageFacade);
  });
  afterEach(() => http.verify());

  function flushRefresh(): void {
    http.expectOne('/api/wallet').flush(wallet);
    http.expectOne('/api/ledger?page=0&size=10').flush(emptyLedger);
  }

  it('reads wallet before ledger so lazy lifecycle entries are included', () => {
    facade.refresh();
    http.expectNone('/api/ledger?page=0&size=10');
    flushRefresh();
    expect(facade.wallet()).toEqual(wallet);
    expect(facade.ledgerPage()).toEqual(emptyLedger);
    expect(facade.loadingWallet()).toBeFalse();
  });

  it('does not let an older wallet response overwrite a newer refresh', () => {
    facade.refresh();
    const oldWallet = http.expectOne('/api/wallet');
    facade.refresh();
    flushRefresh();
    oldWallet.flush({ realBalance: '0.00', bonusBalance: '0.00', bonus: null });
    expect(facade.wallet()).toEqual(wallet);
    http.expectNone('/api/ledger?page=0&size=10');
  });

  it('does not let an older ledger response overwrite a newer refresh', () => {
    facade.refresh();
    http.expectOne('/api/wallet').flush(wallet);
    const oldLedger = http.expectOne('/api/ledger?page=0&size=10');
    facade.refresh();
    flushRefresh();
    oldLedger.flush({ ...emptyLedger, totalElements: 99, totalPages: 10 });
    expect(facade.ledgerPage()).toEqual(emptyLedger);
  });

  it('creates one pending deposit with no wallet refresh or automatic completion', () => {
    facade.createDeposit('20.00');
    facade.createDeposit('20.00');
    expect(facade.depositBusy()).toBeTrue();
    const request = http.expectOne('/api/deposits');
    expect(request.request.body).toEqual({ amount: '20.00' });
    request.flush({ depositId: 'deposit-id', amount: '20.00', status: 'PENDING' });
    expect(facade.deposit()?.status).toBe('PENDING');
    expect(facade.depositBusy()).toBeFalse();
    http.expectNone('/api/wallet');
  });

  it('completes the stored deposit through the demo API and refreshes balances and history', () => {
    facade.createDeposit('20');
    http.expectOne('/api/deposits').flush({ depositId: 'deposit-id', amount: '20.00', status: 'PENDING' });
    facade.completeDeposit();
    facade.completeDeposit();
    const request = http.expectOne('/api/demo/deposits/deposit-id/complete');
    expect(request.request.body).toEqual({});
    expect(request.request.headers.has('X-Signature')).toBeFalse();
    request.flush({ depositId: 'deposit-id', status: 'COMPLETED' });
    flushRefresh();
    expect(facade.deposit()?.status).toBe('COMPLETED');
  });

  it('refreshes after an insufficient-funds rejection which may have committed expiration', () => {
    facade.playRound('5.00', '0.00');
    http.expectOne('/api/rounds/play').flush({ code: 'INSUFFICIENT_FUNDS', detail: 'private details' },
      { status: 409, statusText: 'Conflict' });
    expect(facade.roundError()).toBe('errorInsufficientFunds');
    flushRefresh();
    expect(facade.roundResult()).toBeNull();
  });

  it('keeps money as strings, ignores double submission and refreshes after settlement', () => {
    facade.playRound('4.00', '10.00');
    facade.playRound('4.00', '10.00');
    const request = http.expectOne('/api/rounds/play');
    expect(request.request.body).toEqual({ stake: '4.00', totalWin: '10.00' });
    request.flush({ roundId: 'round-id', stake: '4.00', totalWin: '10.00', realBalance: '16.00', bonusBalance: '0.00' });
    flushRefresh();
    expect(facade.roundResult()?.realBalance).toBe('16.00');
    expect(facade.roundBusy()).toBeFalse();
  });

  it('maps unknown errors to a safe key rather than server details', () => {
    facade.createDeposit('20');
    http.expectOne('/api/deposits').flush({ code: 'UNKNOWN', detail: 'SQL or secret' },
      { status: 500, statusText: 'Internal Server Error' });
    expect(facade.depositError()).toBe('errorGeneric');
    expect(facade.depositBusy()).toBeFalse();
  });

  it('invalidates reads that started before a financial mutation', () => {
    facade.refresh();
    http.expectOne('/api/wallet').flush(wallet);
    const staleLedger = http.expectOne('/api/ledger?page=0&size=10');
    facade.playRound('1.00', '0.00');
    staleLedger.flush({ ...emptyLedger, totalElements: 1, totalPages: 1 });
    expect(facade.ledgerPage()).toBeNull();
    http.expectOne('/api/rounds/play').flush({ roundId: 'new', stake: '1.00', totalWin: '0.00', realBalance: '19.00', bonusBalance: '20.00' });
    flushRefresh();
    expect(facade.ledgerPage()).toEqual(emptyLedger);
  });

  it('ignores an older failed refresh after a newer one has succeeded', () => {
    facade.refresh();
    const oldWallet = http.expectOne('/api/wallet');
    facade.refresh();
    flushRefresh();
    oldWallet.flush(null, { status: 500, statusText: 'Internal Server Error' });
    expect(facade.walletError()).toBeFalse();
    expect(facade.wallet()).toEqual(wallet);
  });

  it('loads a requested ledger page and ignores out-of-range navigation', () => {
    facade.refresh();
    http.expectOne('/api/wallet').flush(wallet);
    http.expectOne('/api/ledger?page=0&size=10').flush({ ...emptyLedger, totalElements: 12, totalPages: 2 });
    facade.goToPage(1);
    http.expectOne('/api/ledger?page=1&size=10').flush({ ...emptyLedger, page: 1, totalElements: 12, totalPages: 2 });
    facade.goToPage(2);
    facade.goToPage(-1);
    expect(facade.ledgerPage()?.page).toBe(1);
    http.expectNone(request => request.url === '/api/ledger');
  });
});
