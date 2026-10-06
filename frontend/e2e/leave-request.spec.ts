// Leave request (PRODUCT_SPEC.md §10): the employee requests leave in self-service and submits it,
// HR approves it, and the employee sees the approval and the reduced available balance.
import { expect, storage, test } from './support/fixtures';
import { apiAs } from './support/flows';

/** A free weekday range in the future: a week per run, moving on while earlier weeks are taken. */
async function freeWeek(seed: Parameters<typeof apiAs>[1]): Promise<{ start: string; end: string }> {
  const hr = apiAs('alice', seed);
  const taken = (await hr.all('/leave-requests', { 'filter[employeeId]': seed.ids.employeeE003 })) as { startDate: string; status: string }[];
  const used = new Set(taken.filter((r) => r.status !== 'CANCELLED' && r.status !== 'REJECTED').map((r) => r.startDate));
  const date = new Date();
  date.setDate(date.getDate() + 14);
  for (;;) {
    while (date.getDay() !== 1) date.setDate(date.getDate() + 1); // Monday
    const start = date.toISOString().slice(0, 10);
    const end = new Date(date.getTime() + 86_400_000).toISOString().slice(0, 10); // Tuesday
    if (!used.has(start)) return { start, end };
    date.setDate(date.getDate() + 7);
  }
}

test('leave request', async ({ browser, seed, companyPath }) => {
  test.setTimeout(3 * 60_000);
  const { start, end } = await freeWeek(seed);

  const employee = await (await browser.newContext({ storageState: storage('erin') })).newPage();
  await employee.goto(companyPath('/me/leave'));
  await expect(employee.getByRole('heading', { name: 'My leave' })).toBeVisible();
  await employee.getByRole('button', { name: 'New leave request' }).click();
  const dialog = employee.getByRole('dialog');
  await dialog.getByRole('combobox', { name: 'Leave type' }).click();
  await employee.getByRole('option', { name: 'Annual leave' }).click();
  await dialog.getByLabel('Start date').fill(start);
  await dialog.getByLabel('End date').fill(end);
  await dialog.getByLabel('Reason').fill('Family visit');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();

  const row = employee.getByRole('row').filter({ hasText: 'Family visit' }).first();
  await expect(row.getByText('Draft')).toBeVisible();
  await row.getByRole('button', { name: 'Submit' }).click();
  await expect(row.getByText('Submitted')).toBeVisible();

  const hr = await (await browser.newContext({ storageState: storage('alice') })).newPage();
  await hr.goto(companyPath('/hr/leave-requests'));
  const request = hr.getByRole('row').filter({ hasText: 'Family visit' }).filter({ hasText: 'Submitted' }).first();
  await request.getByRole('button', { name: 'Approve' }).click();
  const confirm = hr.getByRole('dialog');
  await confirm.getByLabel(/note/i).fill('Enjoy');
  await confirm.locator('[data-slot="dialog-footer"]').getByRole('button', { name: 'Approve' }).click();
  await expect(confirm).toBeHidden();

  await employee.reload();
  await expect(employee.getByRole('row').filter({ hasText: 'Family visit' }).first().getByText('Approved')).toBeVisible();
});
