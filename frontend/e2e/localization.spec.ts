// Bangla is the default language, English the secondary one (docs/LOCALIZATION.md): first visit,
// switching both ways and remembering it, the profile's choice after sign-in on another device, Bangla
// screens, validation and search, and the same figures in both languages.
import type { Browser, BrowserContext, Page } from '@playwright/test';
import { SPA_ORIGIN } from './support/global-setup';
import { expect, expectAccessible, storage, test } from './support/fixtures';
import { apiAs } from './support/flows';

/** Bengali digits as ASCII, group separators removed: the value a figure shows. */
const value = (text: string) => text.replace(/[০-৯]/g, (d) => String(d.charCodeAt(0) - 0x09e6)).replace(/[^\d.-]/g, '');

async function signedIn(browser: Browser, user: 'alice' | 'bob' | 'erin', language: 'bn' | 'en'): Promise<BrowserContext> {
  const context = await browser.newContext({ storageState: storage(user) });
  await context.addInitScript((lang) => localStorage.setItem('erp.language', lang), language);
  return context;
}

async function signIn(page: Page, email: string, password: string, labels: { email: string; password: string; submit: string }) {
  await page.getByLabel(labels.email).fill(email);
  await page.getByLabel(labels.password).fill(password);
  await page.getByRole('button', { name: labels.submit, exact: true }).click();
  await expect(page.getByRole('navigation', { name: /প্রধান নেভিগেশন|Main navigation/ })).toBeVisible();
}

test('a first visit is in Bangla; the choice is switched, remembered and follows the user', async ({ browser, seed }) => {
  test.setTimeout(3 * 60_000);
  const erin = seed.users.erin;
  const first = await browser.newContext();
  const page = await first.newPage();
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'সাইন ইন' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'bn-BD');
  await expect(page.getByRole('button', { name: 'বাংলা' })).toHaveAttribute('aria-pressed', 'true');

  await page.getByRole('button', { name: 'English' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await page.getByRole('button', { name: 'বাংলা' }).click();
  await expect(page.getByRole('heading', { name: 'সাইন ইন' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'সাইন ইন' })).toBeVisible();

  // Bangla throughout after sign-in; the choice made before it becomes the profile's.
  await signIn(page, erin.email, erin.password, { email: 'ইমেইল', password: 'পাসওয়ার্ড', submit: 'সাইন ইন' });
  await expect(page.getByRole('navigation', { name: 'প্রধান নেভিগেশন' }).getByText('স্ব-সেবা')).toBeVisible();
  await expectAccessible(page, 'Bangla dashboard');

  // English chosen while signed in is saved in the profile …
  await page.getByRole('banner').getByRole('button', { name: 'English' }).click();
  await expect(page.getByRole('navigation', { name: 'Main navigation' }).getByText('Self-service')).toBeVisible();
  await first.close();

  // … so on another device (no saved choice there) the sign-in page is Bangla and the user's English follows.
  const second = await browser.newContext();
  const other = await second.newPage();
  await other.goto('/login');
  await expect(other.getByRole('heading', { name: 'সাইন ইন' })).toBeVisible();
  await signIn(other, erin.email, erin.password, { email: 'ইমেইল', password: 'পাসওয়ার্ড', submit: 'সাইন ইন' });
  await expect(other.getByRole('navigation', { name: 'Main navigation' }).getByText('Self-service')).toBeVisible();
  await other.getByRole('banner').getByRole('button', { name: 'বাংলা' }).click();
  await expect(other.getByRole('navigation', { name: 'প্রধান নেভিগেশন' }).getByText('স্ব-সেবা')).toBeVisible();
  await second.close();

  // Leave the demo user without a saved language for the other specs.
  const me = await apiAs('erin', seed).get('/api/v1/me');
  await apiAs('erin', seed).patch('/api/v1/me', { locale: null }, { ifMatch: me.user.version });
});

test('Bangla screens: navigation, tables, validation and search', async ({ browser, seed, companyPath, problems }) => {
  test.setTimeout(3 * 60_000);
  const name = 'আয়েশা এন্টারপ্রাইজ';
  const alice = apiAs('alice', seed);
  const existing = await alice.get('/partners', { q: 'আয়েশা' });
  if (!existing.data.length) await alice.post('/partners', { code: 'AYESHA', name, partnerType: 'ORGANIZATION' });

  const context = await signedIn(browser, 'alice', 'bn');
  const page = await context.newPage();
  page.on('response', (r) => {
    const url = new URL(r.url());
    if (url.pathname.startsWith('/api/') && r.status() >= 400 && !url.pathname.endsWith('/me/employee')) problems.api.push(`${r.status()} ${url.pathname}`);
  });
  await page.goto(companyPath('/sales/orders'));
  await expect(page.getByRole('heading', { name: 'বিক্রয় আদেশ', level: 1 })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'অবস্থা', exact: true })).toBeVisible();
  await expect(page.getByRole('table').getByText(/পোস্টকৃত|নিশ্চিত|ডেলিভারিকৃত|বন্ধ|খসড়া/).first()).toBeVisible();
  await expectAccessible(page, 'Bangla sales orders');

  // Search by a Bangla name.
  await page.goto(companyPath('/org/partners'));
  await page.getByRole('searchbox').or(page.getByPlaceholder('অনুসন্ধান…')).first().fill('আয়েশা');
  await expect(page.getByRole('table').getByText(name)).toBeVisible();

  // Validation in Bangla.
  await page.getByRole('button', { name: 'নতুন পক্ষ' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'তৈরি করুন' }).click();
  await expect(page.getByRole('dialog').getByText('আবশ্যক').first()).toBeVisible();
  expect(problems.api).toEqual([]);
  expect(problems.console).toEqual([]);
  await context.close();
});

test('accounting, inventory and payroll figures are the same in both languages', async ({ browser, seed, companyPath }) => {
  test.setTimeout(3 * 60_000);
  const alice = apiAs('alice', seed);
  const invoice = (await alice.get('/invoices', { 'filter[status]': 'POSTED', limit: 1 })).data[0];
  const run = (await apiAs('bob', seed).get('/payroll-runs', { 'filter[status]': 'PAID', limit: 1 })).data[0];
  const figures = async (language: 'bn' | 'en') => {
    const context = await signedIn(browser, 'alice', language);
    const page = await context.newPage();
    await page.goto(companyPath(`/sales/invoices/${invoice.id}`));
    const total = await page
      .locator('dl > div')
      .filter({ has: page.getByRole('term').getByText(language === 'bn' ? 'সর্বমোট' : 'Total', { exact: true }) })
      .getByRole('definition')
      .innerText();
    await page.goto(companyPath('/inventory/stock'));
    const stock = await page.getByRole('row').filter({ hasText: 'WIDGET' }).first().innerText();
    await page.goto(companyPath(`/payroll/runs/${run.id}`));
    const net = await page.getByText(language === 'bn' ? 'নিট বেতন' : 'Net', { exact: true }).first().locator('..').innerText();
    await context.close();
    return { total: value(total), stock: value(stock.split('\t').slice(-3).join(' ')), net: value(net) };
  };
  const bangla = await figures('bn');
  const english = await figures('en');
  expect(bangla.total).not.toBe('');
  expect(bangla).toEqual(english);
});

test('every page of the navigation renders in Bangla', async ({ browser, problems, companyPath }) => {
  test.setTimeout(10 * 60_000);
  const context = await signedIn(browser, 'alice', 'bn');
  const page = await context.newPage();
  page.on('response', (r) => {
    const url = new URL(r.url());
    if (url.pathname.startsWith('/api/') && r.status() >= 400 && !url.pathname.endsWith('/me/employee')) problems.api.push(`${r.status()} ${url.pathname}`);
  });
  page.on('pageerror', (e) => problems.console.push(e.message));
  await page.goto(companyPath(''));
  const nav = page.getByRole('navigation', { name: 'প্রধান নেভিগেশন' });
  await expect(nav.locator('a[href]').first()).toBeVisible();
  const collapsed = nav.locator('button[aria-expanded="false"]');
  while ((await collapsed.count()) > 0) await collapsed.first().click();
  // No English navigation labels in the Bangla interface.
  expect(await nav.innerText()).not.toMatch(/[A-Za-z]{3,}/);
  const links = [...new Set(await nav.locator('a[href]').evaluateAll((a) => a.map((l) => l.getAttribute('href')!)))];
  for (const href of links) {
    await page.goto(href);
    await expect(page.locator('main h1').first(), href).toBeVisible();
    await page.waitForLoadState('networkidle');
    await expectAccessible(page, href);
  }
  expect(problems.api).toEqual([]);
  expect(problems.console).toEqual([]);
  expect(SPA_ORIGIN).toContain('http');
  await context.close();
});
