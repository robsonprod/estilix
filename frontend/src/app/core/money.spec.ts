import { describe, expect, it } from 'vitest';
import { brl, toDecimal, toInput } from './money';

/** Task 5.1: money never goes through floats. */
describe('money', () => {
  it('formats API decimals as reais', () => {
    expect(brl('45.00')).toBe('R$ 45,00');
    expect(brl('1234.5')).toBe('R$ 1.234,50');
    expect(brl('0.00')).toBe('R$ 0,00');
    expect(brl(null)).toBe('');
  });

  it('parses what people type into API decimals', () => {
    expect(toDecimal('45')).toBe('45.00');
    expect(toDecimal('45,5')).toBe('45.50');
    expect(toDecimal('R$ 1.234,56')).toBe('1234.56');
    expect(toDecimal('80.00')).toBe('80.00');
    expect(toDecimal('007')).toBe('7.00');
  });

  it('refuses negative, garbage and too many decimals', () => {
    expect(toDecimal('-1')).toBeNull();
    expect(toDecimal('abc')).toBeNull();
    expect(toDecimal('1,234')).toBeNull();
    expect(toDecimal('')).toBeNull();
  });

  it('shows API decimals in the price input with a comma', () => {
    expect(toInput('45.00')).toBe('45,00');
  });
});
