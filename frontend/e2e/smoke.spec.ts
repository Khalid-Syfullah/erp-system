// Every page reachable from the navigation renders against the real API without failed calls, page
// errors or serious accessibility violations (WCAG 2.2 AA, axe). The pages are read from the
// rendered sidebar, so the test follows the role-aware navigation of each user.
import type { Page } from '@playwright/test';
import { expectAccessible, storage, test, expect, type Problems } from './support/fixtures';

async function navigationLinks(page: Page): Promise<string[]> {
  const nav = page.getByRole('navigation', { name: 'Main navigation' }).first();
  await expect(nav.locator('a[href]').first()).toBeVisible();
  const collapsed = nav.locator('button[aria-expanded="false"]');
  while ((await collapsed.count()) > 0) await collapsed.first().click();
  const hrefs = await nav.locator('a[href]').evaluateAll((links) => links.map((a) => a.getAttribute('href')!));
  return [...new Set(hrefs)];
}

async function checkPage(page: Page, problems: Problems, href: string) {
  problems.api.length = 0;
  problems.console.length = 0;
  await page.goto(href);
  await expect(page.locator('main h1').first(), href).toBeVisible();
  await page.waitForLoadState('networkidle');
  await expectAccessible(page, href);
  expect(problems.api, href).toEqual([]);
  expect(problems.console, href).toEqual([]);
}

for (const user of ['alice', 'bob', 'erin', 'admin'] as const) {
  test.describe(`navigation of ${user}`, () => {
    test.use({ storageState: storage(user) });
    test('every page renders', async ({ page, problems }) => {
      test.setTimeout(10 * 60_000);
      await page.goto('/');
      await expect(page.locator('main h1').first()).toBeVisible();
      const links = await navigationLinks(page);
      expect(links.length).toBeGreaterThan(0);
      // SMOKE_ONLY=/org,/admin narrows the run while a module is built.
      const only = process.env.SMOKE_ONLY?.split(',').filter(Boolean);
      const selected = only ? links.filter((href) => only.some((part) => href.includes(part))) : links;
      for (const href of selected) await checkPage(page, problems, href);
    });
  });
}

// Detail pages of seeded records, with every tab opened.
test.describe('detail pages (alice)', () => {
  test.use({ storageState: storage('alice') });
  test('render with their tabs', async ({ page, problems, seed, companyPath }) => {
    test.setTimeout(5 * 60_000);
    const details = [
      `/org/partners/${seed.ids.partnerACME}`,
      `/org/partners/${seed.ids.partnerCUST1}`,
      `/hr/employees/${seed.ids.employeeE003}`,
      `/inventory/products/${seed.ids.productWIDGET}`,
      `/inventory/warehouses/${seed.ids.warehouse}`,
      `/inventory/movements/${seed.ids.openingMovement}`,
      `/inventory/movements/new`,
    ];
    const only = process.env.SMOKE_ONLY?.split(',').filter(Boolean);
    for (const path of details.filter((p) => !only || only.some((part) => p.includes(part)))) {
      await checkPage(page, problems, companyPath(path));
      for (const tab of await page.getByRole('tab').all()) {
        await tab.click();
        await page.waitForLoadState('networkidle');
        await expectAccessible(page, `${path} tab ${await tab.textContent()}`);
      }
      expect(problems.api, path).toEqual([]);
      expect(problems.console, path).toEqual([]);
    }
  });
});

// The first record of each document list, opened from the list as a user would.
const documentLists = [
  '/inventory/movements', '/inventory/counts',
  '/procurement/requisitions', '/procurement/orders', '/procurement/receipts', '/procurement/returns', '/procurement/bills',
  '/sales/quotations', '/sales/orders', '/sales/deliveries', '/sales/returns', '/sales/invoices', '/sales/price-lists',
  '/accounting/entries', '/accounting/payments', '/accounting/expenses', '/accounting/bank-accounts', '/accounting/receivables', '/accounting/payables',
  '/payroll/runs', '/payroll/periods',
];

test.describe('document pages (alice)', () => {
  test.use({ storageState: storage('alice') });
  test('open from their lists', async ({ page, problems, companyPath }) => {
    test.setTimeout(10 * 60_000);
    const only = process.env.SMOKE_ONLY?.split(',').filter(Boolean);
    await page.goto('/');
    await expect(page.locator('main h1').first()).toBeVisible();
    const visible = await navigationLinks(page);
    const lists = documentLists
      .filter((p) => visible.some((href) => href.endsWith(p)))
      .filter((p) => !only || only.some((part) => p.includes(part)));
    for (const list of lists) {
      await checkPage(page, problems, companyPath(list));
      const first = page.locator('main [data-row-link]').first();
      if ((await first.count()) === 0) continue;
      await first.click();
      await expect(page.locator('main h1').first()).toBeVisible();
      await page.waitForLoadState('networkidle');
      await expectAccessible(page, `${list} → ${page.url()}`);
      expect(problems.api, page.url()).toEqual([]);
      expect(problems.console, page.url()).toEqual([]);
    }
  });
});

// The page search (Ctrl K / "Search pages") opens, filters the navigation and goes to the chosen page.
test.describe('page search (alice)', () => {
  test.use({ storageState: storage('alice') });
  test('finds and opens a page', async ({ page, problems, companyPath }) => {
    await page.goto(companyPath(''));
    await expect(page.locator('main h1').first()).toBeVisible();
    await page.keyboard.press('Control+k');
    await page.getByPlaceholder('Go to…').fill('purchase orders');
    await page.getByRole('option', { name: /Purchase orders/ }).first().click();
    await expect(page).toHaveURL(/\/procurement\/orders$/);
    await expect(page.locator('main h1').first()).toHaveText('Purchase orders');
    await page.getByRole('button', { name: /Search pages/ }).first().click();
    await expect(page.getByPlaceholder('Go to…')).toBeVisible();
    await expectAccessible(page, 'page search');
    expect(problems.console).toEqual([]);
  });
});
