// i18n-ready strings: every user-visible text comes from a typed message catalog. English is the
// only catalog in v1; a second locale is a new file with the same shape (`satisfies Messages`).
import { en } from './en';

export type Messages = typeof en;

type Leaves<T, P extends string = ''> = {
  [K in keyof T & string]: T[K] extends string ? `${P}${K}` : Leaves<T[K], `${P}${K}.`>;
}[keyof T & string];

export type MessageKey = Leaves<Messages>;
export type MessageVars = Record<string, string | number | null | undefined>;

const catalog: Messages = en;

function lookup(key: string): string | undefined {
  let node: unknown = catalog;
  for (const part of key.split('.')) {
    if (node === null || typeof node !== 'object') return undefined;
    node = (node as Record<string, unknown>)[part];
  }
  return typeof node === 'string' ? node : undefined;
}

function interpolate(text: string, vars?: MessageVars): string {
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = vars[name];
    return value === undefined || value === null ? match : String(value);
  });
}

/** The message for a key, with `{name}` placeholders filled in. */
export function t(key: MessageKey, vars?: MessageVars): string {
  return interpolate(lookup(key) ?? key, vars);
}

/** A message by a key computed at runtime (error codes, enum values), or undefined. */
export function tryT(key: string, vars?: MessageVars): string | undefined {
  const text = lookup(key);
  return text === undefined ? undefined : interpolate(text, vars);
}

/** "PARTIALLY_DELIVERED" → "Partially delivered": the fallback label of unknown enum values (API.md §2). */
export function humanize(value: string): string {
  const words = value.replace(/[_-]+/g, ' ').trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** The label of an enum value: the catalog's `enums` entry, or the humanized value. */
export function enumLabel(value: string | null | undefined): string {
  if (!value) return '';
  return tryT(`enums.${value}`) ?? humanize(value);
}
