import { test, expect, type Page } from '@playwright/test';

/**
 * Adversarial edge cases found by devil's-advocate review, in real Chromium:
 *  #6  — an attachment-only message (no text) must be sendable via the Send button.
 *  #12 — accepting a next-step hint must enable the Send button.
 *  #20 — Escape must cancel a typed #ticket so it is not sent as a ghost mention.
 */

const HOST = '[data-testid="input-bar-host"]';
const EDITOR = `${HOST} [contenteditable="true"]`;
const SEND = `${HOST} button[aria-label="Send (Enter)"]`;

type CallLog = { method: string; args: unknown[] }[];
const getCalls = (page: Page) =>
  page.evaluate(() => (window as unknown as { __harness: { getCalls(): CallLog } }).__harness.getCalls());

async function sendAndGetPayload(page: Page): Promise<{ text: string; mentions: { label: string }[] }> {
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

test.beforeEach(async ({ page }) => {
  await page.goto('/playwright.html');
  await expect(page.locator(EDITOR)).toBeVisible();
});

test('#6 Send button enables for an attachment-only message (no text)', async ({ page }) => {
  await expect(page.locator(SEND)).toBeDisabled();
  await page.evaluate(() =>
    (window as unknown as { _addAttachmentChip: (m: unknown) => void })._addAttachmentChip({
      sha256: 'edge1', mime: 'application/pdf', size: 10, originalFilename: 'a.pdf', kind: 'file', path: '/tmp/a.pdf',
    }),
  );
  await expect(page.locator(`${HOST} [role="listitem"]`)).toHaveCount(1);
  await expect(page.locator(SEND), 'attachment-only is sendable').toBeEnabled();
});

test('#12 accepting a next-step hint enables Send', async ({ page }) => {
  await expect(page.locator(SEND)).toBeDisabled();
  await page.evaluate(() =>
    (window as unknown as { __harness: { setNextStepHint(t: string): void } }).__harness.setNextStepHint('run the tests'),
  );
  const editor = page.locator(EDITOR);
  await editor.click();
  // The hint shows as ghost text on the empty input; Right Arrow promotes it.
  await editor.press('ArrowRight');
  await expect(editor).toContainText('run the tests');
  await expect(page.locator(SEND), 'accepted hint is sendable').toBeEnabled();
});

test('#20 Escape cancels a typed #ticket — not sent as a ghost mention', async ({ page }) => {
  const editor = page.locator(EDITOR);
  await editor.click();
  await editor.pressSequentially('#PROJ-7');
  await editor.press('Escape');
  await editor.pressSequentially(' and more');

  const payload = await sendAndGetPayload(page);
  expect(payload.text).toContain('#PROJ-7'); // still literal text
  expect(payload.mentions.some(m => m.label === 'PROJ-7'), 'no ghost ticket mention').toBe(false);
});
