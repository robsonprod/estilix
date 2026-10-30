import { describe, expect, it } from 'vitest';
import { stepOf, toBrazilian, toDecimal } from './wizard-model';

describe('money input (pt-BR)', () => {
  it('accepts comma or dot and always sends two decimals', () => {
    expect(toDecimal('45')).toBe('45.00');
    expect(toDecimal('45,5')).toBe('45.50');
    expect(toDecimal('R$ 150,00')).toBe('150.00');
    expect(toDecimal('45.99')).toBe('45.99');
  });

  it('leaves invalid text as typed so the API names the error (never rounds)', () => {
    expect(toDecimal('45,999')).toBe('45,999');
    expect(toDecimal('abc')).toBe('abc');
  });

  it('shows decimals with a comma', () => {
    expect(toBrazilian('45.00')).toBe('45,00');
  });
});

describe('stepOf', () => {
  it('maps API field errors to the wizard step that owns them', () => {
    expect(stepOf('establishmentType')).toBe(1);
    expect(stepOf('slug')).toBe(2);
    expect(stepOf('hours[3]')).toBe(2);
    expect(stepOf('services[0].price')).toBe(3);
    expect(stepOf('acceptTerms')).toBe(3);
  });
});
