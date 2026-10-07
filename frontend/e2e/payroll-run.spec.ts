// Payroll run (PRODUCT_SPEC.md §11): the payroll officer creates and calculates a run (an async job),
// the approver (segregation of duties) approves it, posts it to the ledger and marks it paid.
import { expect, storage, test } from './support/fixtures';
import { apiAs } from './support/flows';
import { action, expectStatus, pick } from './support/ui';

test('payroll run', async ({ browser, seed, companyPath }) => {
  test.setTimeout(5 * 60_000);
  // The first open period without a regular run (runs of earlier test runs stay in the ledger).
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

  const officer = await (await browser.newContext({ storageState: storage('alice') })).newPage();
  await officer.goto(companyPath('/payroll/runs'));
  await officer.getByRole('button', { name: 'New payroll run' }).click();
  await pick(officer, 'Period', period!.startDate, / – /);
  await officer.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
  await expect(officer).toHaveURL(/\/payroll\/runs\//);
  await expectStatus(officer, 'Draft');

  await action(officer, 'Calculate');
  // The calculation job runs on the worker (every 15 s); the page polls until it is done.
  await expect(officer.locator('main header').getByText('Calculated', { exact: true })).toBeVisible({ timeout: 90_000 });
  await expect(officer.getByRole('tab', { name: 'Payslips' })).toBeVisible();
  await expect(officer.getByText('E001 — Alice Operations')).toBeVisible();
  // The preparer may not approve their own run: the action is not offered (and the server refuses).
  await expect(officer.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0);
  const runUrl = officer.url();

  const approver = await (await browser.newContext({ storageState: storage('bob') })).newPage();
  await approver.goto(runUrl);
  await action(approver, 'Approve', { confirm: 'Approve' });
  await expectStatus(approver, 'Approved');
  await action(approver, 'Post to the ledger', { confirm: 'Post to the ledger' });
  await expectStatus(approver, 'Posted');
  await action(approver, 'Mark as paid');
  await pick(approver, 'Bank account', 'Operating', /Operating USD/);
  await approver.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
  await expectStatus(approver, 'Paid');
});
