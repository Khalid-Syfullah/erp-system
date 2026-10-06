// The production build's security headers and CSP (SECURITY.md §10.1), checked where the SPA is
// served like in production: `vite preview` or the web image (ERP_SPA_PRODUCTION=1).
import { cacheHeaders, securityHeaders } from '../security-headers.mjs';
import { expect, storage, test } from './support/fixtures';

test.skip(!process.env.ERP_SPA_PRODUCTION, 'runs against a production build (ERP_SPA_PRODUCTION=1)');

test('HTML and assets carry the security headers', async ({ request }) => {
  const html = await request.get('/c/any/route');
  expect(html.status()).toBe(200);
  for (const [name, value] of Object.entries(securityHeaders)) {
    expect(html.headers()[name.toLowerCase()], name).toBe(value);
  }
  const body = await html.text();
  expect(body).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>/); // no inline scripts
  const asset = /src="(\/assets\/[^"]+\.js)"/.exec(body)![1]!;
  const script = await request.get(asset);
  expect(script.status()).toBe(200);
  expect(script.headers()['x-content-type-options']).toBe('nosniff');
  if (process.env.ERP_SPA_IMAGE) {
    expect(html.headers()['cache-control']).toBe(cacheHeaders.html);
    expect(script.headers()['cache-control']).toBe(cacheHeaders.assets);
  }
});

test.describe('the application under the CSP', () => {
  test.use({ storageState: storage('alice') });
  test('works without CSP violations', async ({ page, companyPath, problems }) => {
    const violations: string[] = [];
    page.on('console', (m) => {
      if (/Content Security Policy|Refused to/.test(m.text())) violations.push(m.text());
    });
    await page.goto(companyPath('/sales/orders/new'));
    await expect(page.getByRole('heading', { name: 'New sales order' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Customer' }).click(); // a Radix popover (inline styles)
    await expect(page.getByRole('option').first()).toBeVisible();
    await page.keyboard.press('Escape');
    expect(violations).toEqual([]);
    expect(problems.api).toEqual([]);
  });
});
