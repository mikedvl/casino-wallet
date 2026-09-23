import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the wallet and displays the server balances in EUR without losing precision', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('h1')?.textContent).toBe('Casino Wallet');
    expect(element.textContent).toContain('Loading wallet');
    expect(element.textContent).not.toContain('0.00 EUR');

    const request = http.expectOne('/api/wallet');
    expect(request.request.method).toBe('GET');
    request.flush({ realBalance: '99999999999999999.99', bonusBalance: '12.30' });
    fixture.detectChanges();

    expect(element.textContent).toContain('Real balance');
    expect(element.textContent).toContain('99999999999999999.99 EUR');
    expect(element.textContent).toContain('Bonus balance');
    expect(element.textContent).toContain('12.30 EUR');
    expect(element.textContent).not.toContain('Loading wallet');
  });
});
