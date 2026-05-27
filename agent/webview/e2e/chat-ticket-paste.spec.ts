import { test, expect, type Page } from '@playwright/test';

/**
 * `#`-ticket type + paste interaction, in real Chromium with a real paste event.
 * Regression for the double-`#` bug: typing `#` then pasting `#PROJ-123` left the
 * typed `#` orphaned in front of the pasted chip, so the sent message body
 * contained `##PROJ-123`.
 */

const HOST = '[data-testid="input-bar-host"]';
const EDITOR = `${HOST} [contenteditable="true"]`;
const SEND = `${HOST} button[aria-label="Send (Enter)"]`;

type CallLog = { method: string; args: unknown[] }[];
const getCalls = (page: Page) =>
  page.evaluate(() => (window as unknown as { __harness: { getCalls(): CallLog } }).__harness.getCalls());

// Click Send (bypasses the ticket dropdown's Enter handler) and wait for the
// async handleSend dispatch to land in the spy log, then return the payload.
async function sendAndGetPayload(page: Page): Promise<{ text: string; mentions: { label: string }[] }> {
  await page.locator(SEND).click();
  await expect
    .poll(async () =>
      (await getCalls(page)).some(c => c.method === '_sendMessageWithMentions' || c.method === '_sendMessage'),
    )
    .toBe(true);
  const calls = await getCalls(page);
  const c = [...calls].reverse().find(x => x.method === '_sendMessageWithMentions' || x.method === '_sendMessage')!;
  if (c.method === '_sendMessageWithMentions') return JSON.parse(c.args[0] as string);
  return { text: c.args[0] as string, mentions: [] };
}

test.beforeEach(async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.goto('/playwright.html');
  await expect(page.locator(EDITOR)).toBeVisible();
});

test('typing "#" then pasting "#PROJ-123" does not produce a double-#', async ({ page }) => {
  const editor = page.locator(EDITOR);
  await editor.click();
  await editor.pressSequentially('#'); // open the ticket trigger
  await page.evaluate(() => navigator.clipboard.writeText('#PROJ-123'));
  await editor.press('ControlOrMeta+v'); // real paste event

  // Chip rendered for the ticket.
  await expect(page.locator(`${HOST} [data-mention-label="PROJ-123"]`)).toHaveCount(1);

  const payload = await sendAndGetPayload(page);
  expect(payload.text, 'no orphaned double-#').not.toContain('##');
  expect(payload.text).toContain('#PROJ-123');
  const ticketMentions = payload.mentions.filter(m => m.label === 'PROJ-123');
  expect(ticketMentions.length, 'ticket recorded exactly once').toBe(1);
});

test('pasting "#PROJ-123" into an empty input yields a single clean chip', async ({ page }) => {
  const editor = page.locator(EDITOR);
  await editor.click();
  await page.evaluate(() => navigator.clipboard.writeText('see #PROJ-123 please'));
  await editor.press('ControlOrMeta+v');

  await expect(page.locator(`${HOST} [data-mention-label="PROJ-123"]`)).toHaveCount(1);
  const payload = await sendAndGetPayload(page);
  expect(payload.text).not.toContain('##');
  expect(payload.text).toContain('#PROJ-123');
  expect(payload.mentions.filter(m => m.label === 'PROJ-123').length).toBe(1);
});
