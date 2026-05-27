import { test, expect, type Page } from '@playwright/test';

/**
 * Real-Chromium scroll checks for the virtualized message list (§8 of the
 * harness mounts the real MessageList with many variable-height rows). jsdom has
 * no layout/scroll model, so the scroll *feel* — reaching both ends, the
 * thumb-to-position mapping being sane, and the position staying put when content
 * is appended — can only be verified here. These pin the behaviour the Virtuoso
 * computeItemKey + defaultItemHeight fix targets.
 */

const SCROLLER = '[role="log"][aria-label="scroll-check"]';

// Average data-row index of the rows currently within the scroller viewport,
// computed from bounding rects (robust to Virtuoso's internal positioning).
function midVisibleRow(page: Page): Promise<number> {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel) as HTMLElement | null;
    if (!el) return -1;
    const sr = el.getBoundingClientRect();
    const rows = [...el.querySelectorAll('[data-row]')] as HTMLElement[];
    const visible = rows
      .filter(r => {
        const rr = r.getBoundingClientRect();
        return rr.bottom > sr.top + 2 && rr.top < sr.bottom - 2;
      })
      .map(r => Number(r.dataset.row));
    return visible.length ? Math.round(visible.reduce((a, b) => a + b, 0) / visible.length) : -1;
  }, SCROLLER);
}

const scrollTopTo = (page: Page, fraction: number) =>
  page.locator(SCROLLER).evaluate((el, f) => {
    el.scrollTop = (el.scrollHeight - el.clientHeight) * f;
  }, fraction);

const readScrollTop = (page: Page) => page.locator(SCROLLER).evaluate(el => el.scrollTop);

test.beforeEach(async ({ page }) => {
  await page.goto('/playwright.html');
  await page.locator('[data-testid="section-scroll"]').scrollIntoViewIfNeeded();
  await expect(page.locator(SCROLLER)).toBeVisible();
});

test('the list overflows and both ends are reachable', async ({ page }) => {
  const { scrollH, clientH } = await page.locator(SCROLLER).evaluate(el => ({
    scrollH: el.scrollHeight,
    clientH: el.clientHeight,
  }));
  expect(scrollH).toBeGreaterThan(clientH);

  // Drag to the very bottom — the last row must be reachable (a wildly-off
  // height estimate would overshoot/undershoot and never reveal it).
  await page.locator(SCROLLER).evaluate(el => { el.scrollTop = el.scrollHeight; });
  await expect(page.locator('[data-testid="scroll-row-59"]')).toBeVisible();

  // Back to the top.
  await page.locator(SCROLLER).evaluate(el => { el.scrollTop = 0; });
  await expect(page.locator('[data-testid="scroll-row-0"]')).toBeVisible();
});

test('scrolling to ~50% lands near the middle row (thumb maps to position)', async ({ page }) => {
  await scrollTopTo(page, 0.5);
  await expect.poll(() => midVisibleRow(page)).toBeGreaterThanOrEqual(18);
  const mid50 = await midVisibleRow(page);
  expect(mid50).toBeLessThanOrEqual(42);

  // Scrolling to 25% must land strictly higher up the list than 50%.
  await scrollTopTo(page, 0.25);
  await expect.poll(() => midVisibleRow(page)).toBeLessThan(mid50);
});

test('position stays put when a row is appended at the bottom', async ({ page }) => {
  await scrollTopTo(page, 0.5);
  // Let Virtuoso settle (measure rows around the viewport).
  await expect.poll(() => midVisibleRow(page)).toBeGreaterThan(0);
  const before = await readScrollTop(page);

  await page.locator('[data-testid="scroll-append"]').click();
  await expect(page.locator('[data-testid="scroll-rowcount"]')).toHaveText('rows: 61');

  // Appending below the viewport must not move the user's scroll position.
  const after = await readScrollTop(page);
  expect(Math.abs(after - before)).toBeLessThanOrEqual(4);

  await page.screenshot({ path: 'test-results/chat-scroll-midpoint.png' });
});
