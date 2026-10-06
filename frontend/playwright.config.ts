import { defineConfig, devices } from '@playwright/test';

// End-to-end tests against a running backend (API.md, seeded by e2e/support/seed.ts) and the Vite dev
// server, which proxies /api to it (same origin, as in production).
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './e2e/support/global-setup.ts',
  use: {
    // ERP_SPA_URL points the tests at a production build (vite preview, the web image).
    baseURL: process.env.ERP_SPA_URL ?? process.env.ERP_APP_ORIGIN ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] }, testIgnore: /tablet\.spec\.ts/ },
    { name: 'tablet', use: { ...devices['iPad (gen 7) landscape'], browserName: 'chromium' }, testMatch: /tablet\.spec\.ts/ },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
