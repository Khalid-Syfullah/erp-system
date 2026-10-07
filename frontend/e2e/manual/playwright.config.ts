import { defineConfig, devices } from '@playwright/test';

// Screenshots for the user manual (docs/USER_MANUAL.md): `npm run docs:screenshots` drives the demo
// processes through the real UI against the running local stack and saves docs/manual/images/*.png.
// Not part of the test suite (its files are *.manual.ts, outside the suite's *.spec.ts).
export default defineConfig({
  testDir: '.',
  testMatch: /\.manual\.ts$/,
  timeout: 10 * 60_000,
  expect: { timeout: 30_000 },
  workers: 1,
  reporter: [['list']],
  globalSetup: '../support/global-setup.ts',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.ERP_SPA_URL ?? 'http://localhost:5173',
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    cwd: '../..',
    timeout: 120_000,
  },
});
