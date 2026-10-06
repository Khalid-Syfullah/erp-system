import { expect, type Page } from '@playwright/test';

/** Chooses a record in an EntityPicker combobox: opens it, searches and picks the option. */
export async function pick(page: Page, name: string | RegExp, search: string, option?: string | RegExp) {
  await page.getByRole('combobox', { name }).click();
  const input = page.getByPlaceholder('Search…').last();
  await input.fill(search);
  await page.getByRole('option', { name: option ?? new RegExp(search, 'i') }).first().click();
}

/** Fills a decimal or text input by its accessible name. */
export async function fill(page: Page, name: string | RegExp, value: string) {
  const field = page.getByLabel(name, { exact: typeof name === 'string' }).first();
  await field.fill(value);
}

/** Runs a document action (a button, or an entry of the "More" menu) and confirms its dialog. */
export async function action(page: Page, label: string | RegExp, options: { confirm?: string | RegExp; reason?: string } = {}) {
  const button = page.getByRole('button', { name: label, exact: typeof label === 'string' });
  await button.or(page.getByRole('button', { name: 'More' })).first().waitFor();
  if (await button.count()) {
    await button.first().click();
  } else {
    await page.getByRole('button', { name: 'More' }).click();
    await page.getByRole('menuitem', { name: label }).click();
  }
  if (options.confirm !== undefined || options.reason !== undefined) {
    const dialog = page.getByRole('dialog');
    if (options.reason !== undefined) await dialog.getByLabel(/reason|note/i).fill(options.reason);
    await dialog.locator('[data-slot="dialog-footer"]').getByRole('button', { name: options.confirm ?? label, exact: true }).click();
    await expect(dialog).toBeHidden();
  }
}

export async function expectStatus(page: Page, status: string | RegExp) {
  await expect(page.locator('main header').getByText(status, { exact: typeof status === 'string' }).first()).toBeVisible();
}
