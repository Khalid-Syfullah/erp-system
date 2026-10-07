// Screenshots for docs/USER_MANUAL.md: walks the demo processes through the real UI (the same steps
// as the end-to-end flows) and saves one image per step in docs/manual/images. Run it against the
// seeded local stack with `npm run docs:screenshots`; every run adds new demo documents.
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Browser, Page } from '@playwright/test';
import { freshTotp } from '../support/erp-api';
import { expect, storage, test } from '../support/fixtures';
import { apiAs } from '../support/flows';
import { action, expectStatus, fill, pick } from '../support/ui';

const IMAGES = resolve(import.meta.dirname, '../../../docs/manual/images');
mkdirSync(IMAGES, { recursive: true });

test.describe.configure({ mode: 'serial' });

/** Waits until the page is settled (requests done, dialogs and toasts animated in), then saves it. */
async function shot(page: Page, name: string) {
  // The dev server keeps a socket open, so network idle is best effort.
  await page.waitForLoadState('networkidle', { timeout: 5_000 }).catch(() => undefined);
  await page.waitForTimeout(300);
  await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'));
  // Toasts of the previous step would cover the screen being explained.
  await page.addStyleTag({ content: '[data-sonner-toaster] { display: none !important; }' });
  await page.screenshot({ path: resolve(IMAGES, `${name}.png`) });
}

async function as(browser: Browser, user: 'alice' | 'bob' | 'erin' | 'admin'): Promise<Page> {
  return (await browser.newContext({ storageState: storage(user), viewport: { width: 1440, height: 900 } })).newPage();
}

let invoiceId = '';

test('sign in and find your way', async ({ page, seed, companyPath }) => {
  // The manual is written in English: its screenshots show the English interface (the first visit is Bangla).
  await page.addInitScript(() => localStorage.setItem('erp.language', 'en'));
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await shot(page, '01-sign-in');
  await page.getByLabel(/email/i).fill(seed.users.alice.email);
  await page.getByLabel('Password').fill(seed.users.alice.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByLabel('Verification code')).toBeVisible();
  await page.getByLabel('Verification code').fill(await freshTotp(seed.users.alice.totpSecret!));
  await shot(page, '02-two-step-verification');
  await page.getByRole('button', { name: 'Verify' }).click();
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
  await page.goto(companyPath(''));
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await shot(page, '03-dashboard');
  await page.getByRole('button', { name: /Search pages/ }).click();
  await page.getByPlaceholder('Go to…').fill('purchase');
  await shot(page, '04-search-pages');
});

test('master data', async ({ browser, companyPath }) => {
  const page = await as(browser, 'alice');
  for (const [path, name] of [
    ['/org/partners', '05-partners'],
    ['/inventory/products', '06-products'],
    ['/inventory/stock', '07-stock-levels'],
  ] as const) {
    await page.goto(companyPath(path));
    await expect(page.getByRole('table')).toBeVisible();
    await shot(page, name);
  }
});

test('procure to pay', async ({ browser, companyPath }) => {
  const page = await as(browser, 'alice');
  await page.goto(companyPath('/procurement/orders/new'));
  await pick(page, 'Supplier', 'ACME', /ACME/);
  await pick(page, 'Warehouse', 'WH1', /WH1/);
  await pick(page, 'Item 1', 'WIDGET', /WIDGET/);
  await expect(page.getByRole('combobox', { name: 'Unit 1' })).toHaveText(/EA/);
  await fill(page, 'Quantity 1', '10');
  await fill(page, 'Unit price 1', '12');
  await shot(page, '10-purchase-order-new');
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expectStatus(page, 'Draft');
  await shot(page, '11-purchase-order-draft');
  await action(page, 'Submit');
  await expectStatus(page, 'Pending approval');
  const orderUrl = page.url();

  const approver = await as(browser, 'bob');
  await approver.goto(orderUrl);
  await approver.getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(approver.getByRole('dialog')).toBeVisible();
  await shot(approver, '12-purchase-order-approve');
  await approver.getByRole('dialog').locator('[data-slot="dialog-footer"]').getByRole('button', { name: 'Approve', exact: true }).click();
  await expectStatus(approver, 'Approved');
  await approver.context().close();

  await page.reload();
  await expectStatus(page, 'Approved');
  await shot(page, '13-purchase-order-approved');
  await action(page, 'Receive goods');
  await expect(page).toHaveURL(/\/procurement\/receipts\//);
  await expectStatus(page, 'Draft');
  await shot(page, '14-goods-receipt-draft');
  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');
  await shot(page, '15-goods-receipt-posted');

  await action(page, 'Create bill');
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Supplier invoice no.').fill(`ACME-${Date.now()}`);
  await shot(page, '16-supplier-bill-from-receipt');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(/\/procurement\/bills\//);
  await action(page, 'Check 3-way match');
  await expect(page.getByRole('dialog')).toContainText(/match passed|Matched/i);
  await shot(page, '17-three-way-match');
  await page.keyboard.press('Escape');
  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');
  await expect(page.getByText('Open amount')).toBeVisible();
  await shot(page, '18-supplier-bill-posted');
});

test('order to cash', async ({ browser, companyPath }) => {
  const page = await as(browser, 'alice');
  await page.goto(companyPath('/sales/orders/new'));
  await pick(page, 'Customer', 'CUST1', /CUST1/);
  await pick(page, 'Warehouse', 'WH1', /WH1/);
  await pick(page, 'Item 1', 'WIDGET', /WIDGET/);
  await expect(page.getByRole('combobox', { name: 'Unit 1' })).toHaveText(/EA/);
  await fill(page, 'Quantity 1', '2');
  await page.getByRole('button', { name: 'Calculate prices' }).click();
  await expect(page.getByRole('dialog')).toContainText('$55.00');
  await shot(page, '20-sales-order-price-preview');
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expectStatus(page, 'Draft');
  await shot(page, '21-sales-order-draft');
  await page.getByRole('button', { name: 'Confirm order' }).click();
  const confirm = page.getByRole('dialog');
  await expect(confirm).toContainText('Credit check');
  await shot(page, '22-sales-order-credit-check');
  await confirm.getByRole('button', { name: 'Confirm order' }).click();
  await expectStatus(page, 'Confirmed');
  await shot(page, '23-sales-order-confirmed');

  await action(page, 'Deliver');
  await expect(page).toHaveURL(/\/sales\/deliveries\//);
  await shot(page, '24-delivery-draft');
  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');
  await shot(page, '25-delivery-posted');
  await page.getByRole('link', { name: 'View' }).first().click();
  await expectStatus(page, 'Delivered');

  await action(page, 'Invoice');
  await expect(page).toHaveURL(/\/sales\/invoices\//);
  await shot(page, '26-invoice-draft');
  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');
  await expect(page.getByText('Open amount')).toBeVisible();
  await shot(page, '27-invoice-posted');
  invoiceId = page.url().split('/').pop()!;
});

test('customer payment', async ({ browser, seed, companyPath }) => {
  const invoice = (await apiAs('alice', seed).get(`/invoices/${invoiceId}`)) as { number: string };
  const page = await as(browser, 'alice');
  await page.goto(companyPath('/accounting/payments'));
  await page.getByRole('button', { name: 'New payment' }).click();
  const dialog = page.getByRole('dialog');
  await pick(page, 'Partner', 'CUST1', /CUST1/);
  await pick(page, 'Bank account', 'Operating', /Operating USD/);
  await dialog.getByLabel('Amount').fill('55');
  await shot(page, '30-payment-new');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(/\/accounting\/payments\//);
  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');
  await action(page, 'Allocate');
  const allocate = page.getByRole('dialog');
  await allocate.getByLabel(`Amount ${invoice.number}`).fill('55');
  await expect(allocate.getByText('Allocated now').locator('..')).toContainText('$55.00');
  await shot(page, '31-payment-allocate');
  await allocate.getByRole('button', { name: 'Allocate' }).click();
  await expect(allocate).toBeHidden();
  await shot(page, '32-payment-allocated');
  await page.goto(companyPath(`/sales/invoices/${invoiceId}`));
  await expect(page.getByText('Settled')).toBeVisible();
  await shot(page, '33-invoice-settled');
  await page.goto(companyPath('/accounting/entries'));
  await expect(page.getByRole('table')).toBeVisible();
  await shot(page, '34-journal-entries');
});

test('leave request', async ({ browser, seed, companyPath }) => {
  const taken = (await apiAs('alice', seed).all('/leave-requests', { 'filter[employeeId]': seed.ids.employeeE003 })) as {
    startDate: string;
    endDate: string;
    status: string;
  }[];
  const booked = taken.filter((r) => r.status === 'SUBMITTED' || r.status === 'APPROVED');
  const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const day = new Date();
  day.setDate(day.getDate() + 14);
  let start = '';
  let end = '';
  for (;;) {
    while (day.getDay() !== 1) day.setDate(day.getDate() + 1);
    start = iso(day);
    end = iso(new Date(day.getFullYear(), day.getMonth(), day.getDate() + 1));
    if (booked.every((r) => r.endDate < start || r.startDate > end)) break;
    day.setDate(day.getDate() + 7);
  }
  const reason = `Family visit ${start}`;

  const employee = await as(browser, 'erin');
  await employee.goto(companyPath('/me/leave'));
  await expect(employee.getByRole('heading', { name: 'My leave' })).toBeVisible();
  await employee.getByRole('button', { name: 'New leave request' }).click();
  const dialog = employee.getByRole('dialog');
  await dialog.getByRole('combobox', { name: 'Leave type' }).click();
  await employee.getByRole('option', { name: 'Annual leave' }).click();
  await dialog.getByLabel('Start date').fill(start);
  await dialog.getByLabel('End date').fill(end);
  await dialog.getByLabel('Reason').fill(reason);
  await shot(employee, '40-leave-request-new');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  const row = employee.getByRole('row').filter({ hasText: reason });
  await row.getByRole('button', { name: 'Submit' }).click();
  await expect(row.getByText('Submitted')).toBeVisible();
  await shot(employee, '41-leave-request-submitted');

  const hr = await as(browser, 'alice');
  await hr.goto(companyPath('/hr/leave-requests'));
  await hr.getByRole('row').filter({ hasText: reason }).getByRole('button', { name: 'Approve' }).click();
  const confirm = hr.getByRole('dialog');
  await confirm.getByLabel(/note/i).fill('Enjoy');
  await shot(hr, '42-leave-request-approve');
  await confirm.locator('[data-slot="dialog-footer"]').getByRole('button', { name: 'Approve' }).click();
  await expect(confirm).toBeHidden();

  await employee.reload();
  await expect(employee.getByRole('row').filter({ hasText: reason }).getByText('Approved')).toBeVisible();
  await shot(employee, '43-leave-request-approved');
});

test('payroll run', async ({ browser, seed, companyPath }) => {
  const alice = apiAs('alice', seed);
  const periods = (await alice.all('/payroll-periods', { 'filter[status]': 'OPEN', sort: 'startDate' })) as { id: string; startDate: string }[];
  let period: { id: string; startDate: string } | undefined;
  for (const p of periods) {
    const runs = await alice.get('/payroll-runs', { 'filter[payrollPeriodId]': p.id, 'filter[runType]': 'REGULAR' });
    if (!runs.data.some((r: { status: string }) => r.status !== 'CANCELLED')) {
      period = p;
      break;
    }
  }
  test.skip(!period, 'no open payroll period left');

  const officer = await as(browser, 'alice');
  await officer.goto(companyPath('/payroll/runs'));
  await officer.getByRole('button', { name: 'New payroll run' }).click();
  await pick(officer, 'Period', period!.startDate, new RegExp(period!.startDate));
  await shot(officer, '50-payroll-run-new');
  await officer.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
  await expect(officer).toHaveURL(/\/payroll\/runs\//);
  await action(officer, 'Calculate');
  await expect(officer.locator('main header').getByText('Calculated', { exact: true })).toBeVisible({ timeout: 90_000 });
  await officer.getByRole('tab', { name: 'Payslips' }).click();
  await shot(officer, '51-payroll-run-calculated');
  const runUrl = officer.url();

  const approver = await as(browser, 'bob');
  await approver.goto(runUrl);
  await action(approver, 'Approve', { confirm: 'Approve' });
  await expectStatus(approver, 'Approved');
  await action(approver, 'Post to the ledger', { confirm: 'Post to the ledger' });
  await expectStatus(approver, 'Posted');
  await action(approver, 'Mark as paid');
  await pick(approver, 'Bank account', 'Operating', /Operating USD/);
  await shot(approver, '52-payroll-run-mark-paid');
  await approver.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
  await expectStatus(approver, 'Paid');
  await shot(approver, '53-payroll-run-paid');

  const employee = await as(browser, 'erin');
  await employee.goto(companyPath('/me/payslips'));
  await expect(employee.getByRole('heading', { level: 1 })).toBeVisible();
  await shot(employee, '54-my-payslips');
});

test('reports', async ({ browser, companyPath }) => {
  const page = await as(browser, 'bob');
  await page.goto(companyPath('/reports'));
  await expect(page.getByRole('link', { name: /Sales by customer/ })).toBeVisible();
  await shot(page, '60-report-centre');
  await page.getByRole('link', { name: /Sales by customer/ }).click();
  await page.getByLabel('First date, inclusive').fill(`${new Date().getFullYear()}-01-01`);
  await page.getByRole('button', { name: 'Run report' }).click();
  await expect(page.getByRole('table', { name: 'Sales by customer' }).locator('tfoot')).toContainText('Totals');
  await shot(page, '61-report-sales-by-customer');
  await page.goto(companyPath('/reports/trial-balance'));
  await page.getByLabel('First date, inclusive').fill(`${new Date().getFullYear()}-01-01`);
  await page.getByRole('button', { name: 'Run report' }).click();
  await expect(page.getByRole('table', { name: 'Trial balance' })).toContainText('1010');
  await shot(page, '62-report-trial-balance');
  await page.goto(companyPath('/reports/dashboards'));
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await shot(page, '63-dashboards');
});

test('administration', async ({ browser, companyPath }) => {
  const admin = await as(browser, 'admin');
  for (const [path, name] of [
    ['/admin/users', '70-system-users'],
    ['/admin/roles', '71-roles'],
    ['/admin/companies', '72-companies'],
  ] as const) {
    await admin.goto(path);
    await expect(admin.getByRole('table')).toBeVisible();
    await shot(admin, name);
  }
  const companyAdmin = await as(browser, 'alice');
  await companyAdmin.goto(companyPath('/admin/users'));
  await expect(companyAdmin.getByRole('table')).toBeVisible();
  await shot(companyAdmin, '73-company-users');
  await companyAdmin.goto(companyPath('/admin/audit'));
  await expect(companyAdmin.getByRole('table')).toBeVisible();
  await shot(companyAdmin, '74-audit-log');
});
