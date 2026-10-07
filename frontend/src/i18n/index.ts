// Localization (docs/LOCALIZATION.md). Every user-visible text comes from a typed message catalog:
// Bangla (bn-BD) is the default language, English (en) the secondary language and the fallback for
// any message Bangla lacks. The language is fixed when the application loads (some messages, such as
// form validation texts, are read once at module load); switching it saves the choice and reloads.
import { bn } from './bn';
import { en } from './en';

export type Messages = typeof en;

type Leaves<T, P extends string = ''> = {
  [K in keyof T & string]: T[K] extends string ? `${P}${K}` : Leaves<T[K], `${P}${K}.`>;
}[keyof T & string];

export type MessageKey = Leaves<Messages>;
export type MessageVars = Record<string, string | number | null | undefined>;

export type Language = 'bn' | 'en';

/** The supported languages: their BCP 47 locale for formatting and their name in their own script. */
export const LANGUAGES: Record<Language, { locale: string; label: string }> = {
  bn: { locale: 'bn-BD', label: 'বাংলা' },
  en: { locale: 'en', label: 'English' },
};
export const DEFAULT_LANGUAGE: Language = 'bn';
export const FALLBACK_LANGUAGE: Language = 'en';

/** The browser's saved choice (survives reloads and sign-out); the profile's choice syncs into it. */
const STORAGE_KEY = 'erp.language';
/** Set while a choice made before sign-in still has to be written to the user's profile. */
const PENDING_KEY = 'erp.language.pending';

/** The language of a profile or browser locale ("bn-BD" → bn, "en-GB" → en), or null if unsupported. */
export function languageOf(locale: string | null | undefined): Language | null {
  const prefix = locale?.slice(0, 2).toLowerCase();
  return prefix === 'bn' || prefix === 'en' ? prefix : null;
}

function readStorage(key: string): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string | null) {
  try {
    if (typeof localStorage === 'undefined') return;
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    // Private mode or blocked storage: the choice lasts for this page only.
  }
}

/** The saved choice of this browser, or null if the user never chose. */
export function savedLanguage(): Language | null {
  return languageOf(readStorage(STORAGE_KEY));
}

let active: Language = savedLanguage() ?? DEFAULT_LANGUAGE;

/** The language the application runs in. */
export function language(): Language {
  return active;
}

/** The BCP 47 locale of the active language (bn-BD or en). */
export function activeLocale(): string {
  return LANGUAGES[active].locale;
}

/**
 * Saves an explicit choice in this browser. `pendingProfile` marks a choice made before sign-in, which
 * the profile adopts at the next sign-in (the most recent explicit choice wins).
 */
export function rememberLanguage(next: Language, options: { pendingProfile?: boolean } = {}) {
  writeStorage(STORAGE_KEY, next);
  writeStorage(PENDING_KEY, options.pendingProfile ? '1' : null);
}

/** Whether a choice made before sign-in still has to be written to the profile. */
export function hasPendingProfileLanguage(): boolean {
  return readStorage(PENDING_KEY) === '1';
}

export function clearPendingProfileLanguage() {
  writeStorage(PENDING_KEY, null);
}

/**
 * Switches the running application (tests, and the start-up before the first render). Messages read
 * at module load keep their language until the next reload, so the UI reloads after a user's switch.
 */
export function activateLanguage(next: Language) {
  active = next;
  if (typeof document !== 'undefined') document.documentElement.lang = LANGUAGES[next].locale;
}

const catalogs: Record<Language, unknown> = { bn, en };

function find(catalog: unknown, key: string): string | undefined {
  let node: unknown = catalog;
  for (const part of key.split('.')) {
    if (node === null || typeof node !== 'object') return undefined;
    node = (node as Record<string, unknown>)[part];
  }
  return typeof node === 'string' ? node : undefined;
}

/** The message in the active language, else in the fallback language (English). */
function lookup(key: string): string | undefined {
  return find(catalogs[active], key) ?? (active === FALLBACK_LANGUAGE ? undefined : find(catalogs[FALLBACK_LANGUAGE], key));
}

const counts = new Map<string, Intl.NumberFormat>();

/** Numbers in messages ("{count} selected") use the language's digits; strings are inserted as they are. */
function formatCount(value: number): string {
  const locale = activeLocale();
  let format = counts.get(locale);
  if (!format) {
    format = new Intl.NumberFormat(locale, { maximumFractionDigits: 20 });
    counts.set(locale, format);
  }
  return format.format(value);
}

function interpolate(text: string, vars?: MessageVars): string {
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = vars[name];
    if (value === undefined || value === null) return match;
    return typeof value === 'number' ? formatCount(value) : String(value);
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

/** A message of the active language only (no English fallback): for texts the server already words. */
export function tryOwn(key: string, vars?: MessageVars): string | undefined {
  const text = active === FALLBACK_LANGUAGE ? undefined : find(catalogs[active], key);
  return text === undefined ? undefined : interpolate(text, vars);
}

/**
 * Text prepared for a client-side "contains" search in any script: Unicode NFC (a Bangla vowel sign or
 * conjunct typed as one or several code points compares equal) and lower case (Latin; Bangla has none).
 */
export function searchable(text: string): string {
  return text.normalize('NFC').toLocaleLowerCase();
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

/**
 * Texts the server sends in English (report and dashboard names, report columns and parameters, role
 * and permission names) in the active language: the glossary's translation, else the server's text.
 */
export function serverText(text: string | null | undefined): string {
  if (!text) return '';
  if (active === FALLBACK_LANGUAGE) return text;
  const glossary = (catalogs[active] as { serverText?: Record<string, string> }).serverText;
  return glossary?.[text] ?? text;
}

activateLanguage(active);
