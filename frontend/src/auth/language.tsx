// The language preference (docs/LOCALIZATION.md §4). Order: the signed-in user's saved choice (profile
// `locale`, the same on every device) → this browser's saved choice (`localStorage`, also before sign-in)
// → Bangla. A choice made before sign-in is written to the profile at the next sign-in. Switching saves
// the choice and reloads the application in the new language.
import { useQueryClient } from '@tanstack/react-query';
import { Fragment } from 'react';
import { api, type Schemas } from '@/api/client';
import {
  clearPendingProfileLanguage,
  hasPendingProfileLanguage,
  language,
  languageOf,
  LANGUAGES,
  rememberLanguage,
  savedLanguage,
  t,
  type Language,
} from '@/i18n';
import { cn } from '@/lib/utils';

type User = Schemas['UserResponse'];

/** Where the sign-in page goes next: a reload into the profile's language lands there, not on the sign-in page. */
let signInTarget: string | null = null;

let reloading = false;

/**
 * Runs a sign-in's completion (loading the profile) with `to` as the page a reload into the profile's
 * language opens. Returns true when the page is reloading there.
 */
export async function finishingSignIn(to: string, completion: () => Promise<unknown>): Promise<boolean> {
  signInTarget = to;
  reloading = false;
  try {
    await completion();
    return reloading;
  } finally {
    signInTarget = null;
  }
}

function reload() {
  if (typeof window === 'undefined') return;
  reloading = true;
  if (signInTarget) window.location.assign(signInTarget);
  else window.location.reload();
}

async function saveToProfile(user: User, next: Language): Promise<boolean> {
  try {
    await api.patch('/api/v1/me', {}, { body: { locale: LANGUAGES[next].locale }, ifMatch: user.version });
    return true;
  } catch {
    return false;
  }
}

/**
 * Reconciles the running language with the signed-in user's profile (after GET /me). Returns true
 * when the page reloads into the profile's language.
 */
export async function syncProfileLanguage(user: User | undefined): Promise<boolean> {
  if (!user) return false;
  const local = savedLanguage();
  if (local && hasPendingProfileLanguage()) {
    // Chosen on the sign-in page (or a save that failed): the most recent explicit choice wins.
    if (languageOf(user.locale) === local || (await saveToProfile(user, local))) clearPendingProfileLanguage();
    return false;
  }
  const chosen = languageOf(user.locale);
  if (!chosen) return false;
  rememberLanguage(chosen);
  if (chosen === language()) return false;
  reload();
  return true;
}

/** Saves an explicit choice (this browser and, when signed in, the profile) and reloads in it. */
export async function changeLanguage(next: Language, user: User | undefined) {
  if (next === language() && savedLanguage() === next) return;
  rememberLanguage(next, { pendingProfile: true });
  if (user && (await saveToProfile(user, next))) clearPendingProfileLanguage();
  reload();
}

/** বাংলা | English: the language switcher of the sign-in pages, the header and the account page. */
export function LanguageSwitcher({ className }: { className?: string }) {
  const queryClient = useQueryClient();
  const current = language();
  const choose = (next: Language) => {
    const me = queryClient.getQueryData<Schemas['ProfileResponse']>(['me']);
    void changeLanguage(next, me?.user);
  };
  return (
    <div role="group" aria-label={t('shell.language')} className={cn('flex items-center gap-1 text-sm', className)}>
      {(['bn', 'en'] as const).map((option, index) => (
        <Fragment key={option}>
          {index > 0 ? (
            <span className="text-muted-foreground" aria-hidden>
              |
            </span>
          ) : null}
          <button
            type="button"
            lang={LANGUAGES[option].locale}
            aria-pressed={current === option}
            onClick={() => choose(option)}
            className={cn(
              'rounded px-1.5 py-0.5 underline-offset-4 hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              current === option ? 'font-semibold text-foreground' : 'text-muted-foreground',
            )}
          >
            {LANGUAGES[option].label}
          </button>
        </Fragment>
      ))}
    </div>
  );
}
