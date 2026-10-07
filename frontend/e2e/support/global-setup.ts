import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { APP_ORIGIN, login } from './erp-api';
import { seed } from './seed';

/** The origin the tests open (playwright.config.ts baseURL). */
export const SPA_ORIGIN = new URL(process.env.ERP_SPA_URL ?? process.env.ERP_APP_ORIGIN ?? 'http://localhost:5173').origin;

/**
 * Seeds the demo company, then signs each demo user in once through the API and stores the cookies
 * as Playwright storage states (sign-in is rate limited per account and IP, SECURITY.md §9). The
 * states also hold English as the browser's saved language: the flows assert English texts, while
 * Bangla, the default, has its own spec (localization.spec.ts).
 */
export default async function globalSetup() {
  const state = await seed();
  const dir = resolve(import.meta.dirname, '../.state');
  mkdirSync(dir, { recursive: true });
  for (const [name, credentials] of [...Object.entries(state.users), ['admin', state.admin] as const]) {
    const { session } = await login(credentials);
    // No saved profile language: a choice saved by an earlier run (localization.spec.ts) would win over
    // the English choice stored below.
    const me = await session.get<{ user: { locale?: string | null; version: number } }>('/api/v1/me');
    if (me.user.locale) await session.patch('/api/v1/me', { locale: null }, { ifMatch: me.user.version });
    const url = new URL(APP_ORIGIN);
    writeFileSync(
      resolve(dir, `${name}.storage.json`),
      JSON.stringify({
        cookies: session.browserCookies().map((c) => ({
          name: c.name,
          value: c.value,
          domain: url.hostname,
          path: '/',
          expires: -1,
          httpOnly: c.name !== 'XSRF-TOKEN',
          // The production session cookie is `__Host-` prefixed, which browsers accept only when Secure.
          secure: c.name.startsWith('__Host-') || c.name.startsWith('__Secure-'),
          sameSite: 'Lax' as const,
        })),
        origins: [{ origin: SPA_ORIGIN, localStorage: [{ name: 'erp.language', value: 'en' }] }],
      }),
    );
  }
}
