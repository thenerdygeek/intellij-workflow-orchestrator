import { test, expect } from '@playwright/test';

/**
 * E2E coverage for the chat file/document attachment UI, driven in real
 * Chromium against the mock-bridge harness. Mirrors the manual Playwright-MCP
 * verification: plus-menu → "Attach file…" → chip; image vs file chip
 * rendering; drop-zone overlay toggled by the JVM-pushed `_setDropActive`.
 *
 * Boundary: the Kotlin/JCEF side is mocked. These tests validate the webview
 * layer's contract against the bridge, NOT the native FileChooser / DropTarget
 * / read_document routing (those need `runIde`).
 */

const HOST = '[data-testid="input-bar-host"]';

test.beforeEach(async ({ page }) => {
  await page.goto('/playwright.html');
  await expect(page.locator(HOST)).toBeVisible();
});

test('plus menu exposes "Attach file…" and picking adds a file chip', async ({ page }) => {
  await page.locator(`${HOST} button[aria-label="Add context or skill"]`).click();

  const item = page.getByRole('menuitem', { name: /attach file/i });
  await expect(item).toBeVisible();
  await item.click();

  const chip = page.locator(`${HOST} [role="listitem"]`);
  await expect(chip).toHaveCount(1);
  await expect(chip).toContainText('harness-spec.pdf');
  // A file chip uses a file-type icon, not an <img> thumbnail.
  await expect(chip.locator('img')).toHaveCount(0);
});

test('image attachment renders an <img> thumbnail; file attachment does not', async ({ page }) => {
  await page.evaluate(() =>
    (window as Window & { _addAttachmentChip?: (m: unknown) => void })._addAttachmentChip?.({
      sha256: 'imgsha123',
      mime: 'image/png',
      size: 2048,
      originalFilename: 'shot.png',
      kind: 'image',
    }),
  );

  // Image chips show the filename via the <img alt>/title (a thumbnail), whereas
  // file chips render it as visible text — so locate the image chip by its <img>.
  const chip = page.locator(`${HOST} [role="listitem"]`);
  await expect(chip).toHaveCount(1);
  const img = chip.locator('img');
  await expect(img).toHaveCount(1);
  await expect(img).toHaveAttribute('alt', 'shot.png');
});

test('drop overlay shows while _setDropActive(true) and hides on false', async ({ page }) => {
  const overlay = page.locator(HOST).getByText('Drop files to attach');
  await expect(overlay).toHaveCount(0);

  await page.evaluate(() =>
    (window as Window & { _setDropActive?: (a: boolean) => void })._setDropActive?.(true),
  );
  await expect(overlay).toBeVisible();

  await page.evaluate(() =>
    (window as Window & { _setDropActive?: (a: boolean) => void })._setDropActive?.(false),
  );
  await expect(overlay).toHaveCount(0);
});

test('remove (×) button clears a chip', async ({ page }) => {
  // Add a file chip via the picker, then remove it.
  await page.locator(`${HOST} button[aria-label="Add context or skill"]`).click();
  await page.getByRole('menuitem', { name: /attach file/i }).click();
  const chip = page.locator(`${HOST} [role="listitem"]`);
  await expect(chip).toHaveCount(1);

  await chip.getByRole('button', { name: /remove/i }).click();
  await expect(page.locator(`${HOST} [role="listitem"]`)).toHaveCount(0);
});
