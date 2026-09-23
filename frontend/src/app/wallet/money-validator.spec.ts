import { FormControl } from '@angular/forms';
import { moneyValidator } from './money-validator';

describe('moneyValidator', () => {
  for (const value of ['1', '0.01', '8.00', '99999999999999999.99', '0001.2']) {
    it(`accepts a positive decimal string: ${value}`, () => {
      expect(moneyValidator(false)(new FormControl(value))).toBeNull();
    });
  }
  for (const value of ['0', '0.00', '-1', '8.001', '100000000000000000', 'NaN', '1e2', ' 8 ', '.5', '']) {
    it(`rejects an invalid positive amount: ${value}`, () => {
      expect(moneyValidator(false)(new FormControl(value))).toEqual({ money: true });
    });
  }
  it('permits a zero payout but never a negative payout', () => {
    expect(moneyValidator(true)(new FormControl('0.00'))).toBeNull();
    expect(moneyValidator(true)(new FormControl('-0.01'))).toEqual({ money: true });
  });
});
