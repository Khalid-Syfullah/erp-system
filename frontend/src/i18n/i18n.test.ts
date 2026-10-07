// Localization (docs/LOCALIZATION.md): Bangla is the default, English the secondary and fallback
// language; formatting changes the presentation only.
import Decimal from 'decimal.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fieldMessage } from '@/components/feedback/problem';
import { accountSubtypes, enums } from '@/lib/enums';
import { configureFormatting, formatDate, formatDecimal, formatMoney, parseDecimalInput, toAsciiDigits } from '@/lib/format';
import { bn } from './bn';
import { en } from './en';
import {
  activateLanguage,
  DEFAULT_LANGUAGE,
  enumLabel,
  FALLBACK_LANGUAGE,
  language,
  rememberLanguage,
  savedLanguage,
  searchable,
  serverText,
  t,
  tryT,
} from './index';

/** Every leaf of a catalog as "a.b.c" → text. */
function leaves(node: unknown, prefix = ''): Map<string, string> {
  const out = new Map<string, string>();
  for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') out.set(path, value);
    else for (const [k, v] of leaves(value, path)) out.set(k, v);
  }
  return out;
}

const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

function use(lang: 'bn' | 'en') {
  activateLanguage(lang);
  configureFormatting({ timeZone: 'UTC' });
}

describe('the Bangla catalog', () => {
  const english = leaves(en);
  const bangla = leaves(bn);

  it('translates every English message', () => {
    expect([...english.keys()].filter((key) => !bangla.has(key))).toEqual([]);
  });

  it('keeps the placeholders of every message', () => {
    const differing = [...english].filter(([key, text]) => bangla.has(key) && placeholders(bangla.get(key)!).join() !== placeholders(text).join());
    expect(differing).toEqual([]);
  });

  it('labels every enumeration value the screens offer', () => {
    const values = new Set<string>([...Object.values(enums).flat(), ...Object.values(accountSubtypes).flat()]);
    expect([...values].filter((v) => !(v in bn.enums))).toEqual([]);
  });

  it('leaves no English words in Bangla messages, apart from codes and product names', () => {
    const allowed = /^(ERP|SKU|IBAN|SWIFT\/BIC|FTE|API|PDF|CSV|Excel|XLSX|IANA|Asia\/Dhaka|N|inventory\.adjustment\.approve|procurement\.purchase_order\.approve_high|sales\.order\.override_price)$/;
    const english = [...bangla].filter(([key]) => !key.startsWith('serverText.')).flatMap(([key, text]) =>
      (text.replace(/\{\w+\}/g, '').match(/[A-Za-z][A-Za-z_./]*/g) ?? []).filter((word) => !allowed.test(word)).map((word) => `${key}: ${word}`),
    );
    expect(english).toEqual([]);
  });
});

describe('language choice', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    localStorage.setItem('erp.language', 'en');
    use('en');
  });

  it('is Bangla without a saved choice, with English as the fallback', async () => {
    vi.resetModules();
    const fresh = await import('./index');
    expect(fresh.language()).toBe('bn');
    expect(DEFAULT_LANGUAGE).toBe('bn');
    expect(FALLBACK_LANGUAGE).toBe('en');
    expect(document.documentElement.lang).toBe('bn-BD');
    expect(fresh.t('common.save')).toBe('সংরক্ষণ করুন');
  });

  it('keeps an explicit choice across reloads, in either direction', async () => {
    rememberLanguage('en');
    vi.resetModules();
    expect((await import('./index')).language()).toBe('en');
    expect(savedLanguage()).toBe('en');
    rememberLanguage('bn');
    vi.resetModules();
    expect((await import('./index')).language()).toBe('bn');
  });

  it('falls back to English for a message Bangla lacks', () => {
    use('bn');
    const save = bn.common.save;
    delete (bn.common as Partial<typeof bn.common>).save;
    try {
      expect(t('common.save')).toBe('Save');
      expect(t('common.cancel')).toBe('বাতিল');
    } finally {
      bn.common.save = save;
    }
  });
});

describe('Bangla presentation', () => {
  beforeEach(() => use('bn'));
  afterEach(() => use('en'));

  it('labels enumeration values in Bangla and keeps the internal value', () => {
    expect(enumLabel('APPROVED')).toBe('অনুমোদিত');
    expect(enumLabel('PENDING')).toBe('অপেক্ষমাণ');
    expect(tryT('enums.INVENTORY_ADJUSTMENT')).toBe('মজুত সমন্বয়');
    use('en');
    expect(enumLabel('APPROVED')).toBe('Approved');
  });

  it('writes numbers, amounts and dates the Bangladeshi way', () => {
    expect(formatDecimal('123456')).toBe('১,২৩,৪৫৬');
    expect(formatMoney('25000', 'BDT')).toBe('৳২৫,০০০.০০');
    expect(formatMoney('-1234567.5', 'BDT')).toBe('-৳১২,৩৪,৫৬৭.৫০');
    expect(formatDate('2026-10-07', 'long')).toBe('৭ অক্টোবর, ২০২৬');
    expect(t('common.selectedCount', { count: 3 })).toBe('৩টি নির্বাচিত');
    // Identifiers are text: they keep their characters.
    expect(t('audit.by', { actor: 'INV-2026-000123' })).toBe('INV-2026-000123 কর্তৃক');
  });

  it('reads Bengali and ASCII digits alike into the same decimal', () => {
    expect(parseDecimalInput('১,২৩,৪৫৬.৫০')).toBe('123456.5');
    expect(parseDecimalInput('123456.50')).toBe('123456.5');
    expect(toAsciiDigits('PO-২০২৬')).toBe('PO-2026');
  });

  it('shows the same amounts and quantities in both languages', () => {
    const values = ['0', '0.01', '-7.5', '1234.5678', '99999999999.99', '123456789012345.1234', '-0.0001'];
    for (const value of values) {
      use('bn');
      const bangla = { amount: formatMoney(value, 'BDT', { showCurrency: false }), quantity: formatDecimal(value) };
      use('en');
      const english = { amount: formatMoney(value, 'BDT', { showCurrency: false }), quantity: formatDecimal(value) };
      for (const kind of ['amount', 'quantity'] as const) {
        const read = (text: string) => new Decimal(toAsciiDigits(text).replace(/,/g, ''));
        expect(read(bangla[kind]).equals(read(english[kind])), `${kind} ${value}`).toBe(true);
      }
    }
  });

  it('translates the texts the server words in English, and leaves others alone', () => {
    expect(serverText('Trial balance')).toBe('রেওয়ামিল');
    expect(serverText('Sales by customer')).toBe('গ্রাহকভিত্তিক বিক্রয়');
    expect(serverText('Approve purchase order')).toBe('ক্রয় আদেশ অনুমোদন');
    expect(serverText('Contoso Retail')).toBe('Contoso Retail');
    use('en');
    expect(serverText('Trial balance')).toBe('Trial balance');
  });

  it('words server field errors in Bangla and keeps the server text in English', () => {
    const problem = { pointer: '/lines/0/quantity', code: 'INSUFFICIENT_STOCK', message: 'Requested 5, available 3 at STOCK' };
    expect(fieldMessage(problem)).toBe('পর্যাপ্ত মজুত নেই');
    expect(fieldMessage({ ...problem, code: 'SOMETHING_NEW' })).toBe('Requested 5, available 3 at STOCK');
    use('en');
    expect(fieldMessage(problem)).toBe('Requested 5, available 3 at STOCK');
  });

  it('matches Bangla search text whichever code points were typed', () => {
    const typed = 'আয়েশা';
    const stored = 'আয়েশা এন্টারপ্রাইজ';
    expect(searchable(stored).includes(searchable(typed))).toBe(true);
    expect(language()).toBe('bn');
  });
});
