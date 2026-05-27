import { defineConfig } from '@playwright/test';

/**
 * E2E config for the agent chat webview, run in real Chromium against the
 * mock-bridge harness (`playwright.html` → `HarnessApp`). These tests exercise
 * the React/webview layer only; the Kotlin/JCEF bridge is mocked, so they
 * cannot catch JVM-side bugs (see e2e/README in the spec). CI-ready: the
 * web server is auto-started and the run is headless.
 *
 * Run: `npm run test:e2e` (after `npx playwright install chromium`).
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173/playwright.html',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
