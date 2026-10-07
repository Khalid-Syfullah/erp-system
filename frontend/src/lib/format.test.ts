import { beforeEach, describe, expect, it } from 'vitest';
import {
  configureFormatting,
  formatDate,
  formatDecimal,
  formatMoney,
  parseDecimalInput,
  scaleOf,
  sumDecimals,
} from './format';

describe('decimal formatting', () => {
  beforeEach(() => configureFormatting({ locale: 'en-US', timeZone: 'UTC' }));

  it('formats decimal strings exactly, without binary floats', () => {
    expect(formatDecimal('12345678901234567.89')).toBe('12,345,678,901,234,567.89');
    expect(formatDecimal('0.1')).toBe('0.1');
    expect(formatDecimal('12.500000')).toBe('12.5');
  });

  it('formats money with the currency’s minor units and keeps significant ledger digits', () => {
    expect(formatMoney('75.0000', 'USD')).toBe('$75.00');
    expect(formatMoney('1.2345', 'USD')).toBe('$1.2345');
    expect(formatMoney('1000', 'JPY')).toBe('¥1,000');
    expect(formatMoney('-5', 'USD')).toBe('-$5.00');
  });

  it('parses locale input into canonical decimal strings', () => {
    expect(parseDecimalInput('1,234.50')).toBe('1234.5');
    expect(parseDecimalInput(' 7 ')).toBe('7');
    expect(parseDecimalInput('abc')).toBeNull();
    expect(parseDecimalInput('')).toBeNull();
    // A profile locale refines the interface language's formats (here English with comma decimals).
    configureFormatting({ locale: 'en-DK' });
    expect(parseDecimalInput('1.234,50')).toBe('1234.5');
    // A locale of another language never changes the interface language's formats.
    configureFormatting({ locale: 'de-DE' });
    expect(formatDecimal('1234.5')).toBe('1,234.5');
  });

  it('sums decimal strings exactly', () => {
    expect(sumDecimals(['0.1', '0.2', null, 'x'])).toBe('0.3');
    expect(scaleOf('12.500')).toBe(3);
  });

  it('formats ISO dates as calendar dates', () => {
    expect(formatDate('2026-10-02')).toBe('Oct 2, 2026');
    expect(formatDate(null)).toBe('');
  });
});
