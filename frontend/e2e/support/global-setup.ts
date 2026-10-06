import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { APP_ORIGIN, login } from './erp-api';
import { seed } from './seed';

/**
 * Seeds the demo company, then signs each demo user in once through the API and stores the cookies
 * as Playwright storage states (sign-in is rate limited per account and IP, SECURITY.md §9).
 */
export default async function globalSetup() {
  const state = await seed();
  const dir = resolve(import.meta.dirname, '../.state');
  mkdirSync(dir, { recursive: true });
  for (const [name, credentials] of [...Object.entries(state.users), ['admin', state.admin] as const]) {
    const { session } = await login(credentials);
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
          secure: false,
          sameSite: 'Lax' as const,
        })),
        origins: [],
      }),
    );
  }
}
