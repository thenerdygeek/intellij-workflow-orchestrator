import { test, expect, type Page } from '@playwright/test';

/**
 * Copy-button coverage in real Chromium. Every chat component's copy affordance
 * delegates to the shared <CopyButton>, which in a browser context routes
 * through navigator.clipboard.writeText. These tests assert the clipboard
 * receives the FULL string for both short and long (~15K) inputs — guarding
 * against a caller wiring a truncated/preview string into the button.
 *
 * The expected values are read from hidden source elements in the harness, so
 * the test never duplicates the fixture text.
 */

const readClipboard = (page: Page) => page.evaluate(() => navigator.clipboard.readText());

test.beforeEach(async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.goto('/playwright.html');
  await expect(page.getByTestId('section-copy')).toBeVisible();
});

test('copies the full SHORT string to the clipboard', async ({ page }) => {
  const expected = (await page.getByTestId('copy-short-source').textContent()) ?? '';
  await page.getByTestId('copy-short-host').getByRole('button', { name: /copy short/i }).click();
  expect(await readClipboard(page)).toBe(expected);
});

test('copies the full LONG string with no truncation', async ({ page }) => {
  const expected = (await page.getByTestId('copy-long-source').textContent()) ?? '';
  expect(expected.length, 'fixture is genuinely long').toBeGreaterThan(10_000);

  await page.getByTestId('copy-long-host').getByRole('button', { name: /copy long/i }).click();
  const got = await readClipboard(page);
  expect(got.length, 'full length copied (no truncation)').toBe(expected.length);
  expect(got).toBe(expected);
});

test('copy button shows the "Copied" confirmation state after a copy', async ({ page }) => {
  const btn = page.getByTestId('copy-short-host').getByRole('button', { name: /copy short/i });
  await btn.click();
  // aria-label flips to "Copied" for ~2s on success.
  await expect(page.getByTestId('copy-short-host').getByRole('button', { name: /copied/i })).toBeVisible();
});
