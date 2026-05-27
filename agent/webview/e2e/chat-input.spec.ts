import { test, expect, type Page } from '@playwright/test';

/**
 * Characterization tests for the chat input (RichInput, contentEditable) driven
 * in real Chromium. These assert the *expected* behavior; a failure here is a
 * candidate bug in the chat UI (jsdom/Vitest can't exercise contentEditable
 * keystrokes/undo reliably, so real-browser coverage matters here).
 *
 * Mocked-bridge boundary applies (UI layer only).
 */

const HOST = '[data-testid="input-bar-host"]';
const EDITOR = `${HOST} [contenteditable="true"]`;
const SEND = `${HOST} button[aria-label="Send (Enter)"]`;

type CallLog = { method: string; args: unknown[] }[];
const getCalls = (page: Page) =>
  page.evaluate(() => (window as unknown as { __harness: { getCalls(): CallLog } }).__harness.getCalls());
const clearCalls = (page: Page) =>
  page.evaluate(() => (window as unknown as { __harness: { clearCalls(): void } }).__harness.clearCalls());
const sendCalls = (calls: CallLog) =>
  calls.filter(c => c.method === '_sendMessage' || c.method === '_sendMessageWithMentions');
// Auto-retrying count of send dispatches — handleSend is async, so a one-shot
// getCalls() read can race the dispatch under parallel load. Poll instead.
const expectSendCount = (page: Page, n: number) =>
  expect.poll(async () => sendCalls(await getCalls(page)).length).toBe(n);
const lastSendArgs = async (page: Page) => {
  const sends = sendCalls(await getCalls(page));
  return JSON.stringify(sends[sends.length - 1]?.args ?? []);
};

test.beforeEach(async ({ page }) => {
  await page.goto('/playwright.html');
  await expect(page.locator(EDITOR)).toBeVisible();
});

// Drive keystrokes through the editor locator (auto-focuses the target) rather
// than page.keyboard (global), so keys can't land on the wrong element under
// parallel load — a flake confirmed via Playwright MCP: the behavior is correct,
// only page.keyboard targeting was unreliable.

test('Send is disabled when empty, enabled after typing', async ({ page }) => {
  await expect(page.locator(SEND)).toBeDisabled();
  await page.locator(EDITOR).pressSequentially('hello world');
  await expect(page.locator(SEND)).toBeEnabled();
});

test('clicking Send dispatches the text to the bridge and clears the input', async ({ page }) => {
  await page.locator(EDITOR).pressSequentially('hello world');
  await clearCalls(page);
  await expect(page.locator(SEND)).toBeEnabled();
  await page.locator(SEND).click();

  await expectSendCount(page, 1);
  expect(await lastSendArgs(page), 'send carries the typed text').toContain('hello world');
  await expect(page.locator(EDITOR), 'input clears after send').toHaveText('');
});

test('Enter sends; Shift+Enter inserts a newline without sending', async ({ page }) => {
  const editor = page.locator(EDITOR);
  await editor.pressSequentially('line one');
  await clearCalls(page);
  await editor.press('Shift+Enter');
  await editor.pressSequentially('line two');

  // Newline kept, nothing sent yet.
  await expect(editor, 'newline retained in the editor').toContainText('line one');
  await expectSendCount(page, 0);

  await editor.press('Enter');
  await expectSendCount(page, 1);
  const payload = await lastSendArgs(page);
  expect(payload, 'multi-line text reaches the bridge').toContain('line one');
  expect(payload).toContain('line two');
  await expect(editor, 'input clears after send').toHaveText('');
});

test('Ctrl/Cmd+Z undoes the most recent typing burst (custom undo stack)', async ({ page }) => {
  const editor = page.locator(EDITOR);
  await editor.pressSequentially('first ');
  // The undo stack coalesces typing on a ~400ms idle boundary; wait so the next
  // burst becomes a separate undo entry.
  await page.waitForTimeout(500);
  await editor.pressSequentially('second');
  await expect(editor).toContainText('first second');

  await editor.press('ControlOrMeta+z');
  await expect(editor).toContainText('first');
  await expect(editor).not.toContainText('second');
});
