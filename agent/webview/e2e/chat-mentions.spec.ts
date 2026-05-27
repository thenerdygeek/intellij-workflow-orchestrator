import { test, expect, type Page } from '@playwright/test';

/**
 * @-mention scenarios in real Chromium against the mock-bridge harness
 * (_searchMentions returns alpha.ts in two folders + beta.ts). Exercises
 * dropdown select, same-name-file mentions, and chip removal → payload.
 */

const HOST = '[data-testid="input-bar-host"]';
const EDITOR = `${HOST} [contenteditable="true"]`;
const SEND = `${HOST} button[aria-label="Send (Enter)"]`;

type CallLog = { method: string; args: unknown[] }[];
const getCalls = (page: Page) =>
  page.evaluate(() => (window as unknown as { __harness: { getCalls(): CallLog } }).__harness.getCalls());

async function sendAndGetPayload(page: Page): Promise<{ text: string; mentions: { label: string; path?: string }[] }> {
  await page.locator(SEND).click();
  await expect
    .poll(async () =>
      (await getCalls(page)).some(c => c.method === '_sendMessageWithMentions' || c.method === '_sendMessage'),
    )
    .toBe(true);
  const calls = await getCalls(page);
  const c = [...calls].reverse().find(x => x.method === '_sendMessageWithMentions' || x.method === '_sendMessage')!;
  return c.method === '_sendMessageWithMentions' ? JSON.parse(c.args[0] as string) : { text: c.args[0] as string, mentions: [] };
}

async function openMentionDropdown(page: Page) {
  const editor = page.locator(EDITOR);
  await editor.click();
  await editor.pressSequentially('@a');
  await expect(page.locator('.dropdown-item').first()).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  await page.goto('/playwright.html');
  await expect(page.locator(EDITOR)).toBeVisible();
});

test('selecting an @-file inserts a chip and sends it as a mention', async ({ page }) => {
  await openMentionDropdown(page);
  await page.locator('.dropdown-item').filter({ hasText: 'src/b' }).click();

  await expect(page.locator(`${HOST} [data-mention-label="alpha.ts"]`)).toHaveCount(1);
  const payload = await sendAndGetPayload(page);
  const paths = payload.mentions.map(m => m.path);
  expect(paths).toContain('src/b/alpha.ts');
});

test('two same-named files (different paths) are BOTH sent', async ({ page }) => {
  const editor = page.locator(EDITOR);
  await openMentionDropdown(page);
  await page.locator('.dropdown-item').filter({ hasText: 'src/a' }).click();
  // open the dropdown again and pick the other same-named file
  await editor.pressSequentially(' @a');
  await expect(page.locator('.dropdown-item').first()).toBeVisible();
  await page.locator('.dropdown-item').filter({ hasText: 'src/b' }).click();

  await expect(page.locator(`${HOST} [data-mention-label="alpha.ts"]`)).toHaveCount(2);
  const payload = await sendAndGetPayload(page);
  const paths = payload.mentions.map(m => m.path).sort();
  expect(paths, 'both distinct files in payload').toEqual(['src/a/alpha.ts', 'src/b/alpha.ts']);
});

test('removing a mention chip via × excludes it from the payload', async ({ page }) => {
  const editor = page.locator(EDITOR);
  await openMentionDropdown(page);
  await page.locator('.dropdown-item').filter({ hasText: 'beta.ts' }).click();
  await expect(page.locator(`${HOST} [data-mention-label="beta.ts"]`)).toHaveCount(1);

  // Remove the chip via its × button.
  await page.locator(`${HOST} [data-mention-label="beta.ts"] button[data-remove]`).click();
  await expect(page.locator(`${HOST} [data-mention-label="beta.ts"]`)).toHaveCount(0);

  await editor.pressSequentially('hello after removal');
  const payload = await sendAndGetPayload(page);
  expect(payload.mentions.some(m => m.label === 'beta.ts'), 'removed mention not sent').toBe(false);
});
