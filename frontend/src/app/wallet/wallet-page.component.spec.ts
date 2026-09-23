import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WalletPageComponent } from './wallet-page.component';
import { BonusStatus, LedgerPage, WalletSummary } from './wallet-summary.model';

describe('WalletPageComponent', () => {
  let fixture: ComponentFixture<WalletPageComponent>;
  let http: HttpTestingController;
  let element: HTMLElement;
  const emptyLedger: LedgerPage = { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
  const emptyWallet: WalletSummary = { realBalance: '0.00', bonusBalance: '0.00', bonus: null };
  const ledger: LedgerPage = {
    items: [{
      id: 'ledger-id', walletType: 'BONUS', operationType: 'ROUND_STAKE', amount: '-4.00',
      balanceAfter: '16.00', referenceType: 'GAME_ROUND', referenceId: 'round-id', createdAt: '2026-01-01T12:30:00Z',
    }], page: 0, size: 10, totalElements: 11, totalPages: 2,
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WalletPageComponent], providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(WalletPageComponent);
    element = fixture.nativeElement;
    fixture.detectChanges();
  });
  afterEach(() => {
    http.verify();
    fixture.componentInstance.i18n.setLanguage('en');
  });

  function button(id: string): HTMLButtonElement {
    const found = element.querySelector<HTMLButtonElement>('[data-testid="' + id + '"]');
    if (!found) throw new Error('Missing button: ' + id);
    return found;
  }
  function input(id: string, value: string): void {
    const field = element.querySelector<HTMLInputElement>('#' + id);
    if (!field) throw new Error('Missing input: ' + id);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
  }
  function ready(wallet: WalletSummary = emptyWallet, page: LedgerPage = emptyLedger): void {
    http.expectOne('/api/wallet').flush(wallet);
    http.expectOne('/api/ledger?page=0&size=10').flush(page);
    fixture.detectChanges();
  }
  function language(code: 'en' | 'uk'): void {
    const toggle = element.querySelector<HTMLButtonElement>('button[lang="' + code + '"]');
    if (!toggle) throw new Error('Missing language toggle');
    toggle.click();
    fixture.detectChanges();
  }

  it('renders loading then no-bonus state without inventing balances', () => {
    expect(element.textContent).toContain('Loading wallet');
    expect(element.querySelector('[data-testid="real-balance"]')).toBeNull();
    expect(button('play-round').disabled).toBeTrue();
    ready();
    expect(element.textContent).toContain('No welcome bonus yet');
    expect(element.textContent).toContain('No transactions yet');
    expect(button('previous-page').disabled).toBeTrue();
    expect(button('next-page').disabled).toBeTrue();
  });

  for (const [status, label] of [['ACTIVE', 'Active'], ['COMPLETED', 'Completed'], ['EXPIRED', 'Expired']] as const) {
    it('renders authoritative ' + status + ' bonus metadata and progress', () => {
      const bonusStatus: BonusStatus = status;
      ready({ realBalance: '9007199254740993.01', bonusBalance: '12.30',
        bonus: { status: bonusStatus, initialAmount: '20.00', wageringProgress: '19.99', wageringTarget: '400.00',
          expiresAt: '2026-01-08T12:00:00Z' } });
      expect(element.querySelector('[data-testid="real-balance"]')?.textContent).toContain('9007199254740993.01');
      expect(element.querySelector('[data-testid="bonus-balance"]')?.textContent).toContain('12.30');
      expect(element.querySelector('[data-testid="bonus-status"]')?.textContent).toBe(label);
      expect(element.querySelector('[data-testid="wagering"]')?.textContent).toContain('19.99 / 400.00 EUR');
      expect(element.textContent).toContain('2026-01-08 12:00:00');
    });
  }

  it('shows a safe error, translates it and permits retry', () => {
    http.expectOne('/api/wallet').flush({ detail: 'Internal database details' },
      { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    expect(element.textContent).toContain('Unable to load wallet');
    expect(element.textContent).not.toContain('Internal database details');
    language('uk');
    expect(element.textContent).toContain('Не вдалося завантажити гаманець');
    button('retry-wallet').click();
    fixture.detectChanges();
    expect(element.textContent).toContain('Завантаження гаманця');
    ready();
    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(element.querySelector('[data-testid="real-balance"]')?.textContent).toContain('0.00 EUR');
  });

  it('cancels pending wallet requests when destroyed', () => {
    const request = http.expectOne('/api/wallet');
    fixture.destroy();
    expect(request.cancelled).toBeTrue();
  });

  it('validates deposit input, blocks double clicks and completes a demo deposit', () => {
    ready();
    input('deposit-amount', '0');
    expect(button('create-deposit').disabled).toBeTrue();
    expect(element.textContent).toContain('Enter a positive decimal amount');
    input('deposit-amount', '20.001');
    expect(button('create-deposit').disabled).toBeTrue();
    input('deposit-amount', '20.00');
    button('create-deposit').click();
    fixture.detectChanges();
    expect(button('create-deposit').disabled).toBeTrue();
    button('create-deposit').click();
    const create = http.expectOne('/api/deposits');
    expect(create.request.method).toBe('POST');
    create.flush({ depositId: 'deposit-id', amount: '20.00', status: 'PENDING' });
    fixture.detectChanges();
    expect(element.textContent).toContain('No funds have moved yet');
    expect(element.querySelector('[data-testid="real-balance"]')?.textContent).toContain('0.00');
    button('complete-deposit').click();
    fixture.detectChanges();
    expect(button('complete-deposit').disabled).toBeTrue();
    http.expectOne('/api/demo/deposits/deposit-id/complete').flush({ depositId: 'deposit-id', status: 'COMPLETED' });
    ready({ ...emptyWallet, realBalance: '20.00', bonusBalance: '20.00' });
    expect(element.textContent).toContain('Deposit completed.');
    expect(element.querySelector('[data-testid="complete-deposit"]')).toBeNull();
    expect(element.querySelector('[data-testid="real-balance"]')?.textContent).toContain('20.00');
  });

  it('localizes a generic deposit failure without displaying server content', () => {
    ready();
    input('deposit-amount', '20');
    button('create-deposit').click();
    http.expectOne('/api/deposits').flush({ code: 'UNEXPECTED', detail: 'secret SQL' },
      { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();
    expect(element.textContent).toContain('The request could not be completed');
    expect(element.textContent).not.toContain('secret SQL');
    language('uk');
    expect(element.textContent).toContain('Не вдалося виконати запит');
  });

  it('validates both round amounts and renders the backend result without calculating it', () => {
    ready();
    input('round-stake', '8.001');
    input('round-win', '-1');
    expect(button('play-round').disabled).toBeTrue();
    input('round-stake', '4.00');
    input('round-win', '10.00');
    button('play-round').click();
    fixture.detectChanges();
    expect(button('play-round').disabled).toBeTrue();
    expect(element.textContent).toContain('Settling round');
    http.expectOne('/api/rounds/play').flush({
      roundId: 'round-id', stake: '4.00', totalWin: '10.00', realBalance: '2.50', bonusBalance: '23.50',
    });
    ready({ ...emptyWallet, realBalance: '2.50', bonusBalance: '23.50' });
    expect(element.querySelector('[data-testid="round-result"]')?.textContent).toContain('2.50 EUR');
    expect(element.querySelector('[data-testid="round-result"]')?.textContent).toContain('23.50 EUR');
    expect(element.querySelector('[data-testid="real-balance"]')?.textContent).toContain('2.50 EUR');
  });

  for (const [code, message] of [
    ['INSUFFICIENT_FUNDS', 'Insufficient funds for this stake'],
    ['MAX_BET_EXCEEDED', 'The maximum stake is EUR 5'],
    ['INTERNAL_ERROR', 'The request could not be completed'],
  ]) {
    it('shows safe round error ' + code + ' and refreshes lifecycle state', () => {
      ready();
      input('round-stake', '5.01');
      button('play-round').click();
      http.expectOne('/api/rounds/play').flush({ code, detail: 'internal payload' },
        { status: 409, statusText: 'Conflict' });
      ready();
      expect(element.querySelector('[role="alert"]')?.textContent).toContain(message);
      expect(element.textContent).not.toContain('internal payload');
      expect(element.querySelector('[data-testid="round-result"]')).toBeNull();
    });
  }

  it('renders signed ledger money and paginates with disabled boundary and loading controls', () => {
    ready(emptyWallet, ledger);
    expect(element.querySelector('tbody')?.textContent).toContain('-4.00');
    expect(element.querySelector('tbody')?.textContent).toContain('Round stake');
    expect(element.querySelector('tbody')?.textContent).toContain('16.00');
    expect(button('previous-page').disabled).toBeTrue();
    expect(button('next-page').disabled).toBeFalse();
    button('next-page').click();
    fixture.detectChanges();
    expect(button('next-page').disabled).toBeTrue();
    http.expectOne('/api/ledger?page=1&size=10').flush({ ...ledger, page: 1 });
    fixture.detectChanges();
    expect(button('previous-page').disabled).toBeFalse();
    expect(button('next-page').disabled).toBeTrue();
  });

  it('retries a ledger failure by resolving the wallet before reloading history', () => {
    http.expectOne('/api/wallet').flush(emptyWallet);
    http.expectOne('/api/ledger?page=0&size=10').flush(null, { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();
    expect(element.textContent).toContain('Unable to load transactions');
    button('retry-ledger').click();
    ready(emptyWallet, ledger);
    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(element.querySelector('tbody')?.textContent).toContain('Round stake');
  });

  it('switches labels, ledger enums and document language between EN and UK', () => {
    ready(emptyWallet, ledger);
    language('uk');
    expect(element.querySelector('h1')?.textContent).toBe('Казино-гаманець');
    expect(element.textContent).toContain('Ставка раунду');
    expect(element.textContent).toContain('Ігровий раунд');
    expect(element.textContent).not.toContain('ROUND_STAKE');
    expect(document.documentElement.lang).toBe('uk');
    language('en');
    expect(element.querySelector('h1')?.textContent).toBe('Casino Wallet');
    expect(element.textContent).toContain('Round stake');
    expect(document.documentElement.lang).toBe('en');
  });
});
