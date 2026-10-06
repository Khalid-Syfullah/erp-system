// Period close (PRODUCT_SPEC.md §8.5): the accountant soft-closes a period, the financial controller
// closes it and reopens it with a reason (reopening needs accounting.period.reopen and MFA).
import type { Page } from '@playwright/test';
import { expect, storage, test } from './support/fixtures';

const firstPeriod = (page: Page) => page.getByRole('table', { name: /Fiscal year/ }).first().getByRole('row').nth(1);

async function periodAction(page: Page, label: string, reason?: string) {
  const row = firstPeriod(page);
  const direct = row.getByRole('button', { name: label, exact: true });
  if (await direct.count()) await direct.click();
  else {
    await row.getByRole('button', { name: 'More' }).click();
    await page.getByRole('menuitem', { name: label }).click();
  }
  const dialog = page.getByRole('dialog');
  if (reason) await dialog.getByLabel(/reason/i).fill(reason);
  await dialog.getByRole('button', { name: label, exact: true }).first().click();
  await expect(dialog).toBeHidden();
}

test('period close and reopen', async ({ browser, companyPath }) => {
  test.setTimeout(3 * 60_000);
  const controller = await (await browser.newContext({ storageState: storage('bob') })).newPage();
  const accountant = await (await browser.newContext({ storageState: storage('alice') })).newPage();

  // Start from an open period (a previous failed run may have left it closed).
  await controller.goto(companyPath('/accounting/periods'));
  await expect(firstPeriod(controller)).toBeVisible();
  if (!(await firstPeriod(controller).getByText('Open', { exact: true }).count())) {
    await periodAction(controller, 'Reopen', 'Reset for the end-to-end test');
  }

  await accountant.goto(companyPath('/accounting/periods'));
  await periodAction(accountant, 'Soft-close');
  await expect(firstPeriod(accountant).getByText('Soft-closed')).toBeVisible();
  // The accountant may not close or reopen (ACCOUNTANT role): the actions are not offered.
  await firstPeriod(accountant).getByRole('button', { name: 'More' }).click().catch(() => undefined);
  await expect(accountant.getByRole('menuitem', { name: 'Reopen' })).toHaveCount(0);
  await accountant.keyboard.press('Escape');

  await controller.reload();
  await periodAction(controller, 'Close');
  await expect(firstPeriod(controller).getByText('Closed', { exact: true })).toBeVisible();
  await periodAction(controller, 'Reopen', 'Late supplier invoice to be booked');
  await expect(firstPeriod(controller).getByText('Open', { exact: true })).toBeVisible();
});
