// Locale formatting of the API's decimal strings, dates and timestamps (API.md §11).
//
// Amounts and quantities arrive as decimal strings and are never converted to binary floats:
// Intl.NumberFormat formats a numeric string exactly, and decimal.js does any arithmetic the UI
// needs for display (sums of entered allocations, for instance). Business amounts (line totals,
// taxes, balances) always come from the server (PRODUCT_SPEC.md G-7).
//
// Numbers, amounts and dates follow the interface language (docs/LOCALIZATION.md): Bangla uses bn-BD
// (Bengali digits, lakh/crore grouping: ১,২৩,৪৫৬; ৳২৫,০০০.০০), English uses en. Only the presentation
// changes: values, scales and rounding are the same in every language, and identifiers (numbers of
// documents, SKUs, codes, account and phone numbers) are text and are never re-digitized.
import Decimal from 'decimal.js';
import { activeLocale, language, languageOf } from '@/i18n';

export interface FormatSettings {
  locale: string;
  timeZone: string | undefined;
}

let settings: FormatSettings = {
  locale: activeLocale(),
  timeZone: undefined,
};

const cache = new Map<string, Intl.NumberFormat | Intl.DateTimeFormat>();

/**
 * Applies the signed-in user's profile locale and time zone (GET /me). A profile locale refines the
 * interface language (en-GB for English dates in British order) but never changes it: a locale of
 * another language is ignored, so Bangla screens always show Bangla numbers.
 */
export function configureFormatting(next: Partial<FormatSettings>): void {
  const matches = next.locale && languageOf(next.locale) === language() && isSupportedLocale(next.locale);
  const locale = matches ? next.locale! : activeLocale();
  const timeZone = next.timeZone && isSupportedTimeZone(next.timeZone) ? next.timeZone : settings.timeZone;
  if (locale !== settings.locale || timeZone !== settings.timeZone) {
    settings = { locale, timeZone };
    cache.clear();
  }
}

function isSupportedLocale(locale: string): boolean {
  try {
    return Intl.NumberFormat.supportedLocalesOf([locale.replace('_', '-')]).length > 0;
  } catch {
    return false;
  }
}

function isSupportedTimeZone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat('en', { timeZone });
    return true;
  } catch {
    return false;
  }
}

function numberFormat(key: string, options: Intl.NumberFormatOptions): Intl.NumberFormat {
  const id = `n|${key}`;
  let format = cache.get(id) as Intl.NumberFormat | undefined;
  if (!format) {
    format = new Intl.NumberFormat(settings.locale.replace('_', '-'), options);
    cache.set(id, format);
  }
  return format;
}

function dateFormat(key: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const id = `d|${key}`;
  let format = cache.get(id) as Intl.DateTimeFormat | undefined;
  if (!format) {
    format = new Intl.DateTimeFormat(settings.locale.replace('_', '-'), options);
    cache.set(id, format);
  }
  return format;
}

const DECIMAL = /^-?\d+(\.\d+)?$/;

export function isDecimalString(value: unknown): value is string {
  return typeof value === 'string' && DECIMAL.test(value);
}

/** Fraction digits of a decimal string ("12.500" → 3). */
export function scaleOf(value: string): number {
  const dot = value.indexOf('.');
  return dot < 0 ? 0 : value.length - dot - 1;
}

function asNumeric(value: string | number): Intl.StringNumericLiteral | number {
  return typeof value === 'number' ? value : (value as Intl.StringNumericLiteral);
}

/**
 * Formats a decimal string for display. Trailing zeros beyond `minimumFractionDigits` are dropped
 * ("12.500000" → "12.5"), but the value is never rounded unless `maximumFractionDigits` asks for it.
 */
export function formatDecimal(
  value: string | number | null | undefined,
  options: { minimumFractionDigits?: number; maximumFractionDigits?: number } = {},
): string {
  if (value === null || value === undefined || value === '') return '';
  if (typeof value === 'string' && !DECIMAL.test(value)) return value;
  const min = options.minimumFractionDigits ?? 0;
  const max = Math.max(min, options.maximumFractionDigits ?? (typeof value === 'string' ? Math.min(scaleOf(value), 20) : 6));
  return numberFormat(`dec|${min}|${max}`, {
    minimumFractionDigits: min,
    maximumFractionDigits: max,
    roundingMode: 'halfExpand',
  } as Intl.NumberFormatOptions).format(asNumeric(value));
}

/** Minor units of an ISO currency (USD 2, JPY 0, KWD 3), as Intl knows them. */
export function currencyDigits(currency: string): number {
  try {
    return numberFormat(`cur-digits|${currency}`, { style: 'currency', currency }).resolvedOptions()
      .maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

/**
 * Formats an amount in a currency. Ledger amounts carry four decimals ("75.0000"); they are shown with
 * the currency's minor units unless the value has significant digits beyond them.
 */
export function formatMoney(
  value: string | number | null | undefined,
  currency?: string | null,
  options: { showCurrency?: boolean } = {},
): string {
  if (value === null || value === undefined || value === '') return '';
  if (typeof value === 'string' && !DECIMAL.test(value)) return value;
  const digits = currency ? currencyDigits(currency) : 2;
  const significant =
    typeof value === 'string' ? Math.min(Math.max(digits, significantScale(value)), 6) : digits;
  if (!currency || options.showCurrency === false) {
    return formatDecimal(value, { minimumFractionDigits: digits, maximumFractionDigits: significant });
  }
  try {
    const format = numberFormat(`money|${currency}|${digits}|${significant}`, {
      style: 'currency',
      currency,
      minimumFractionDigits: digits,
      maximumFractionDigits: significant,
      roundingMode: 'halfExpand',
    } as Intl.NumberFormatOptions);
    return language() === 'bn' ? symbolFirst(format.formatToParts(asNumeric(value))) : format.format(asNumeric(value));
  } catch {
    return `${formatDecimal(value, { minimumFractionDigits: digits })} ${currency}`;
  }
}

/**
 * bn-BD writes the currency after the amount (২৫,০০০.০০৳); Bangladeshi usage puts the sign first
 * (৳২৫,০০০.০০, -৳৫০০.০০). The parts are only reordered: digits and precision are Intl's.
 */
function symbolFirst(parts: Intl.NumberFormatPart[]): string {
  const sign = parts.filter((p) => p.type === 'minusSign' || p.type === 'plusSign').map((p) => p.value).join('');
  const symbol = parts.filter((p) => p.type === 'currency').map((p) => p.value).join('');
  const amount = parts
    .filter((p) => p.type !== 'minusSign' && p.type !== 'plusSign' && p.type !== 'currency')
    .map((p) => p.value)
    .join('')
    .trim();
  return `${sign}${symbol}${amount}`;
}

/** Scale without trailing zeros ("75.0000" → 0, "1.2340" → 3). */
function significantScale(value: string): number {
  const dot = value.indexOf('.');
  if (dot < 0) return 0;
  let end = value.length;
  while (end > dot + 1 && value[end - 1] === '0') end--;
  return end - dot - 1;
}

export function formatPercent(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '';
  return `${formatDecimal(value, { maximumFractionDigits: 4 })} %`;
}

/** The locale's decimal and group separators. */
export function separators(): { decimal: string; group: string } {
  const parts = numberFormat('sep', { useGrouping: true }).formatToParts(12345.6);
  return {
    decimal: parts.find((p) => p.type === 'decimal')?.value ?? '.',
    group: parts.find((p) => p.type === 'group')?.value ?? ',',
  };
}

/** Bengali digits (০–৯, as typed on a Bangla keyboard or shown in bn-BD inputs) as ASCII digits. */
export function toAsciiDigits(text: string): string {
  return text.replace(/[\u09E6-\u09EF]/g, (d) => String(d.charCodeAt(0) - 0x09e6));
}

/**
 * Parses what a user typed in a decimal field ("1,234.50", "1.234,50" in de-DE, "1 234,5") into the
 * API's canonical decimal string ("1234.50"). Returns null for anything that is not a plain number.
 */
export function parseDecimalInput(text: string): string | null {
  const trimmed = toAsciiDigits(text).trim();
  if (trimmed === '') return null;
  const { decimal, group } = separators();
  let normalized = trimmed.replace(/[\s\u00a0\u202f]/g, '');
  if (group !== decimal) normalized = normalized.split(group).join('');
  if (decimal !== '.') {
    if (normalized.includes('.') && !normalized.includes(decimal)) {
      // A dot typed in a comma locale is taken as the decimal point when it is the only separator.
    } else {
      normalized = normalized.split(decimal).join('.');
    }
  }
  normalized = normalized.replace(/^\+/, '');
  if (!DECIMAL.test(normalized)) return null;
  return new Decimal(normalized).toFixed();
}

/** Formats a canonical decimal string for an input box, without grouping, in the user's locale. */
export function decimalForInput(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '';
  const { decimal } = separators();
  return decimal === '.' ? value : value.replace('.', decimal);
}

/** Exact sum of decimal strings (for display totals of what the user entered). */
export function sumDecimals(values: (string | null | undefined)[]): string {
  return values
    .filter((v): v is string => isDecimalString(v))
    .reduce((total, v) => total.plus(v), new Decimal(0))
    .toFixed();
}

export function compareDecimals(a: string, b: string): number {
  return new Decimal(a).comparedTo(b);
}

// Dates are ISO local dates (YYYY-MM-DD); timestamps are UTC instants (API.md §11).

/** Formats an ISO date (no time zone shift: it is a calendar date). */
export function formatDate(value: string | null | undefined, style: 'medium' | 'short' | 'long' = 'medium'): string {
  if (!value) return '';
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return value;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return dateFormat(`date|${style}`, { dateStyle: style, timeZone: 'UTC' }).format(date);
}

/** Formats a UTC timestamp in the user's time zone. */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return dateFormat(`ts|${settings.timeZone ?? ''}`, {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: settings.timeZone,
  }).format(date);
}

/** Formats the time of a UTC timestamp in the user's time zone. */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return dateFormat(`time|${settings.timeZone ?? ''}`, { timeStyle: 'short', timeZone: settings.timeZone }).format(date);
}

/** Today's date in the user's time zone, as YYYY-MM-DD. */
export function todayIso(): string {
  return toIsoDate(new Date());
}

/** A Date as the YYYY-MM-DD calendar date it is in the user's time zone. */
export function toIsoDate(date: Date): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: settings.timeZone,
  }).formatToParts(date);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')}`;
}

/** An ISO date as a local Date at midnight, for date pickers. */
export function fromIsoDate(value: string | null | undefined): Date | undefined {
  if (!value) return undefined;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  return match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : undefined;
}

/** A local Date (from a date picker) as YYYY-MM-DD. */
export function localDateToIso(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function addDaysIso(value: string, days: number): string {
  const date = fromIsoDate(value) ?? new Date();
  date.setDate(date.getDate() + days);
  return localDateToIso(date);
}

export function startOfMonthIso(value: string = todayIso()): string {
  return `${value.slice(0, 7)}-01`;
}

