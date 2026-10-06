// Payment allocation (PRODUCT_SPEC.md §8.8): a customer receipt is posted and allocated to the
// open receivable of a posted invoice, which becomes settled.
import { expect, storage, test } from './support/fixtures';
import { postedInvoice } from './support/flows';
import { action, expectStatus, pick } from './support/ui';

test.use({ storageState: storage('alice') });

test('payment allocation', async ({ page, seed, companyPath, problems }) => {
  test.setTimeout(3 * 60_000);
  const invoice = await postedInvoice(seed, '2');

  await page.goto(companyPath('/accounting/payments'));
  await page.getByRole('button', { name: 'New payment' }).click();
  const dialog = page.getByRole('dialog');
  await pick(page, 'Partner', 'CUST1', /CUST1/);
  await pick(page, 'Bank account', 'Operating', /Operating USD/);
  await dialog.getByLabel('Amount').fill('55');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(/\/accounting\/payments\//);
  await expectStatus(page, 'Draft');

  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');

  await action(page, 'Allocate');
  const allocate = page.getByRole('dialog');
  await allocate.getByLabel(`Amount ${invoice.number}`).fill('55');
  await expect(allocate.getByText('Allocated now').locator('..')).toContainText('$55.00');
  await allocate.getByRole('button', { name: 'Allocate' }).click();
  await expect(allocate).toBeHidden();
  await expect(page.getByRole('table').getByText(invoice.number)).toBeVisible();

  await page.goto(companyPath(`/sales/invoices/${invoice.id}`));
  await expect(page.getByText('Settled')).toBeVisible();
  expect(problems.console).toEqual([]);
});
