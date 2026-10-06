// Order-to-cash (PRODUCT_SPEC.md §9): sales order priced from the price list → confirmation with
// the credit check → delivery (stock issue) → invoice from the order → posting.
import { expect, storage, test } from './support/fixtures';
import { action, expectStatus, fill, pick } from './support/ui';

test.use({ storageState: storage('alice') });

test('order to cash', async ({ page, companyPath, problems }) => {
  test.setTimeout(4 * 60_000);
  await page.goto(companyPath('/sales/orders/new'));
  await pick(page, 'Customer', 'CUST1', /CUST1/);
  await pick(page, 'Warehouse', 'WH1', /WH1/);
  await pick(page, 'Item 1', 'WIDGET', /WIDGET/);
  await expect(page.getByRole('combobox', { name: 'Unit 1' })).toHaveText(/EA/);
  await fill(page, 'Quantity 1', '2');

  // Prices come from the server: preview, then save without entering a price.
  await page.getByRole('button', { name: 'Calculate prices' }).click();
  const preview = page.getByRole('dialog');
  await expect(preview).toContainText('$55.00'); // 2 × 25 + 10 % tax
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Save draft' }).click();

  await expectStatus(page, 'Draft');
  await expect(page.getByText('$55.00')).toBeVisible();
  await page.getByRole('button', { name: 'Confirm order' }).click();
  const confirm = page.getByRole('dialog');
  await expect(confirm).toContainText('Credit check');
  await confirm.getByRole('button', { name: 'Confirm order' }).click();
  await expectStatus(page, 'Confirmed');

  await action(page, 'Deliver');
  await expect(page).toHaveURL(/\/sales\/deliveries\//);
  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');
  await page.getByRole('link', { name: 'View' }).first().click();
  await expectStatus(page, 'Delivered');

  await action(page, 'Invoice');
  await expect(page).toHaveURL(/\/sales\/invoices\//);
  await expect(page.getByText('$55.00').first()).toBeVisible();
  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');
  await expect(page.getByText('Open amount')).toBeVisible();

  expect(problems.console).toEqual([]);
});
