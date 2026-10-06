// The report centre (API.md §17.11): run a report from its catalogue parameters, sort it on the
// server, render an accounting statement, export a file and save the report.
import { expect, storage, test } from './support/fixtures';

test.use({ storageState: storage('bob') });

test('report centre', async ({ page, companyPath, problems }) => {
  test.setTimeout(3 * 60_000);
  await page.goto(companyPath('/reports'));
  await page.getByRole('link', { name: /Sales by customer/ }).click();
  await page.getByLabel('First date, inclusive').fill(`${new Date().getFullYear()}-01-01`);
  await page.getByRole('button', { name: 'Run report' }).click();
  const table = page.getByRole('table', { name: 'Sales by customer' });
  await expect(table.getByText('Contoso Retail')).toBeVisible();
  await expect(table.locator('tfoot')).toContainText('Totals');
  await table.getByRole('button', { name: 'Net sales' }).click();
  await expect(table.getByRole('columnheader', { name: 'Net sales' })).toHaveAttribute('aria-sort', 'ascending');

  // Export (asynchronous job) and save with the parameters.
  await page.getByRole('button', { name: 'Export' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Export queued')).toBeVisible();
  await page.getByRole('button', { name: 'Save as…' }).click();
  await page.getByRole('dialog').getByLabel('Name').fill(`Customers ${Date.now()}`);
  await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();

  await page.goto(companyPath('/reports/exports'));
  await expect(page.getByRole('cell', { name: 'sales-by-customer' }).first()).toBeVisible();

  await page.goto(companyPath('/reports/trial-balance'));
  await page.getByLabel('First date, inclusive').fill(`${new Date().getFullYear()}-01-01`);
  await page.getByRole('button', { name: 'Run report' }).click();
  await expect(page.getByRole('table', { name: 'Trial balance' })).toContainText('1010');
  expect(problems.api).toEqual([]);
  expect(problems.console).toEqual([]);
});
