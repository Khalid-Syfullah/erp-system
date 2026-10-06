import AxeBuilder from '@axe-core/playwright';
import { expect, test as base, type Page } from '@playwright/test';
import { resolve } from 'node:path';
import { readSeedState, type SeedState } from './seed';

export const storage = (user: 'alice' | 'bob' | 'erin' | 'admin') => resolve(import.meta.dirname, `../.state/${user}.storage.json`);

/** API failures and page errors collected while a test runs; tests assert there are none. */
export interface Problems {
  api: string[];
  console: string[];
}

export const test = base.extend<{ seed: SeedState; problems: Problems; companyPath: (path: string) => string }>({
  seed: async ({}, use) => use(readSeedState()),
  companyPath: async ({ seed }, use) => use((path: string) => `/c/${seed.companyId}${path}`),
  problems: async ({ page }, use) => {
    const problems: Problems = { api: [], console: [] };
    page.on('response', (response) => {
      const url = new URL(response.url());
      if (!url.pathname.startsWith('/api/') || response.status() < 400) return;
      // The self-service menu probes the employee link; a user without one gets 404 by design.
      if (url.pathname.endsWith('/me/employee') && response.status() === 404) return;
      problems.api.push(`${response.request().method()} ${url.pathname}${url.search} → ${response.status()}`);
    });
    page.on('pageerror', (error) => problems.console.push(error.message));
    page.on('console', (message) => {
      if (message.type() === 'error' && !/Failed to load resource/.test(message.text())) problems.console.push(message.text());
    });
    await use(problems);
  },
});

/** Runs axe (WCAG 2.2 AA rules) on the page; serious and critical violations fail the test. */
export async function expectAccessible(page: Page, context: string) {
  // Let enter animations (fades of dialogs and drawers) finish: mid-fade colours fail contrast checks.
  await page.evaluate(() => Promise.all(document.getAnimations().map((a) => a.finished.catch(() => undefined))));
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(
    serious.map((v) => `${context}: ${v.id} (${v.impact}) ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`),
  ).toEqual([]);
}

export { expect };
