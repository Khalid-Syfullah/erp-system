// Warehouse screens on a tablet (DEVELOPMENT_PLAN.md Phase 11): a stock count is started, counted
// with large touch targets and completed; the layout has no horizontal scrolling.
import { expect, expectAccessible, storage, test } from './support/fixtures';
import { pick } from './support/ui';

test.use({ storageState: storage('alice') });

test('stock count on a tablet', async ({ page, companyPath, problems }) => {
  test.setTimeout(3 * 60_000);
  await page.goto(companyPath('/inventory/counts'));
  await page.getByRole('button', { name: 'New count' }).click();
  await pick(page, 'Warehouse', 'WH1', /WH1/);
  await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(/\/inventory\/counts\//);

  await page.getByRole('button', { name: 'Start count' }).click();
  await expect(page.locator('main header').getByText('In progress')).toBeVisible();
  const cards = page.getByRole('list', { name: 'Lines' }).getByRole('listitem');
  await expect(cards.first()).toBeVisible();

  // Touch targets: the counted-quantity inputs are at least 44 px high.
  const input = cards.first().getByLabel('Counted');
  expect((await input.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  const count = await cards.count();
  for (let i = 0; i < count; i++) {
    const card = cards.nth(i);
    const system = (await card.locator('.tabular').first().textContent())!.replace(/[^\d.]/g, '');
    await card.getByLabel('Counted').fill(system || '0');
  }
  await page.getByRole('button', { name: 'Save counts' }).click();
  await expect(page.getByText('Saved').first()).toBeVisible();
  await page.getByRole('button', { name: 'Complete count' }).click();
  await expect(page.locator('main header').getByText('Completed')).toBeVisible();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  expect(overflow).toBe(false);
  await expectAccessible(page, 'stock count (tablet)');
  expect(problems.api).toEqual([]);
});
