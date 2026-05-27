import { test, expect, type Page, type Locator } from '@playwright/test';

/**
 * Visual overlap checks in real Chromium. jsdom has no layout, so element
 * collisions (e.g. the tool row's hover copy button landing on top of the
 * elapsed-time text) can only be caught here with real bounding boxes.
 *
 * `expectNoOverlap` compares the rects of two locators, ignoring any element
 * that is not actually visible (display:none / visibility:hidden / opacity≈0 /
 * zero-size) — a hidden element occupies no visual space, so it can't collide.
 */

interface Box { left: number; right: number; top: number; bottom: number; visible: boolean }

async function boxOf(loc: Locator): Promise<Box> {
  return loc.evaluate((el) => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    const visible =
      cs.visibility !== 'hidden' &&
      cs.display !== 'none' &&
      parseFloat(cs.opacity || '1') > 0.05 &&
      r.width > 0 &&
      r.height > 0;
    return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, visible };
  });
}

function intersects(a: Box, b: Box): boolean {
  return !(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top);
}

async function expectNoOverlap(a: Locator, b: Locator, label: string) {
  const [ba, bb] = [await boxOf(a), await boxOf(b)];
  if (!ba.visible || !bb.visible) return; // a hidden element can't visually collide
  expect(
    intersects(ba, bb),
    `${label}: expected no overlap but rects intersect — A=${JSON.stringify(ba)} B=${JSON.stringify(bb)}`,
  ).toBe(false);
}

test.beforeEach(async ({ page }) => {
  await page.goto('/playwright.html');
});

test('tool row: hover copy button does not overlap the elapsed time', async ({ page }) => {
  const host = page.locator('[data-testid="tool-overlap-host"]');
  await host.scrollIntoViewIfNeeded();

  // First tool row (read_file, 1.2s). Hover it to reveal the copy button.
  const row = host.locator('.group\\/tool-row').first();
  const copy = row.getByRole('button', { name: /copy tool call summary/i });
  const elapsed = row.getByTestId('tool-elapsed');

  await row.hover();
  // Wait for the copy button to be fully revealed (it fades in).
  await expect.poll(async () => (await boxOf(copy)).visible).toBe(true);

  await expectNoOverlap(copy, elapsed, 'tool copy button vs elapsed time');
});

test('tool rows: no copy/elapsed overlap on any row', async ({ page }) => {
  const host = page.locator('[data-testid="tool-overlap-host"]');
  await host.scrollIntoViewIfNeeded();
  const rows = host.locator('.group\\/tool-row');
  const n = await rows.count();
  expect(n).toBeGreaterThan(0);

  for (let i = 0; i < n; i++) {
    const row = rows.nth(i);
    await row.hover();
    const copy = row.getByRole('button', { name: /copy tool call summary/i });
    await expect.poll(async () => (await boxOf(copy)).visible).toBe(true);
    await expectNoOverlap(copy, row.getByTestId('tool-elapsed'), `row ${i} copy vs elapsed`);
  }
});
