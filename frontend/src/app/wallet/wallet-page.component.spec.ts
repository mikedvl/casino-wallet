import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WalletPageComponent } from './wallet-page.component';

describe('WalletPageComponent', () => {
  let fixture: ComponentFixture<WalletPageComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WalletPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(WalletPageComponent);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('shows a safe error and lets the user retry when the backend is unavailable', () => {
    const element: HTMLElement = fixture.nativeElement;
    http.expectOne('/api/wallet').flush(
      { message: 'Internal database details' },
      { status: 503, statusText: 'Service Unavailable' },
    );
    fixture.detectChanges();

    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Unable to load wallet');
    expect(element.textContent).not.toContain('Internal database details');
    expect(element.querySelector('dd')).toBeNull();
    const retryButton = element.querySelector<HTMLButtonElement>('button');
    if (retryButton === null) {
      fail('Expected a retry button after the wallet request failed');
      return;
    }
    retryButton.click();
    fixture.detectChanges();
    expect(element.querySelector('[role="status"]')?.textContent).toContain('Loading wallet');
    expect(element.querySelector('button')).toBeNull();

    http.expectOne('/api/wallet').flush({ realBalance: '0.00', bonusBalance: '0.00' });
    fixture.detectChanges();
    expect(Array.from(element.querySelectorAll('dd'), balance => balance.textContent?.trim()))
      .toEqual(['0.00 EUR', '0.00 EUR']);
    expect(element.querySelector('[role="alert"]')).toBeNull();
  });

  it('cancels the pending wallet request when the component is destroyed', () => {
    const request = http.expectOne('/api/wallet');
    fixture.destroy();
    expect(request.cancelled).toBeTrue();
  });
});
