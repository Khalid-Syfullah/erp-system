// Procure-to-pay (PRODUCT_SPEC.md §7): purchase order → approval by a second user (segregation of
// duties) → goods receipt → supplier bill from the receipt → 3-way match → posting.
import { expect, storage, test } from './support/fixtures';
import { action, expectStatus, fill, pick } from './support/ui';

test.use({ storageState: storage('alice') });

test('procure to pay', async ({ page, browser, companyPath, problems }) => {
  test.setTimeout(4 * 60_000);
  await page.goto(companyPath('/procurement/orders/new'));
  await pick(page, 'Supplier', 'ACME', /ACME/);
  await pick(page, 'Warehouse', 'WH1', /WH1/);
  await pick(page, 'Item 1', 'WIDGET', /WIDGET/);
  await expect(page.getByRole('combobox', { name: 'Unit 1' })).toHaveText(/EA/);
  await fill(page, 'Quantity 1', '10');
  await fill(page, 'Unit price 1', '12');
  await page.getByRole('button', { name: 'Save draft' }).click();

  await expectStatus(page, 'Draft');
  await expect(page.getByText('$132.00')).toBeVisible(); // 10 × 12 + 10 % tax, computed by the server
  await action(page, 'Submit');
  await expectStatus(page, 'Pending approval');
  const orderUrl = page.url();

  // The approver is another user (SoD): the creator may not approve their own order.
  const approver = await browser.newContext({ storageState: storage('bob') });
  const approverPage = await approver.newPage();
  await approverPage.goto(orderUrl);
  await action(approverPage, 'Approve', { confirm: 'Approve' });
  await expectStatus(approverPage, 'Approved');
  await approver.close();

  await page.reload();
  await expectStatus(page, 'Approved');
  await action(page, 'Receive goods');
  await expect(page).toHaveURL(/\/procurement\/receipts\//);
  await expectStatus(page, 'Draft');
  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');

  await action(page, 'Create bill');
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Supplier invoice no.').fill(`ACME-${Date.now()}`);
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(/\/procurement\/bills\//);
  await action(page, 'Check 3-way match');
  await expect(page.getByRole('dialog')).toContainText(/match passed|Matched/i);
  await page.keyboard.press('Escape');
  await action(page, 'Post', { confirm: 'Post' });
  await expectStatus(page, 'Posted');
  await expect(page.getByText('Open amount')).toBeVisible();

  expect(problems.console).toEqual([]);
});
