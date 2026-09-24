import { ValidatorFn } from '@angular/forms';

// Validate decimal syntax/range without converting authoritative money to a JS number.
export function moneyValidator(allowZero: boolean): ValidatorFn {
  return control => {
    const value: unknown = control.value;
    if (typeof value !== 'string' || !/^\d+(\.\d{1,2})?$/.test(value)) return { money: true };
    const integer = value.split('.')[0].replace(/^0+/, '');
    if (integer.length > 17 || (!allowZero && !/[1-9]/.test(value))) return { money: true };
    return null;
  };
}
