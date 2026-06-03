import { test, expect } from '@playwright/test';

/**
 * E2E for the memory-write approval card (Section 5 of the memory-write-approval
 * feature). Renders the real ApprovalView in Chromium via the harness §10 section,
 * driven by the real `approvalTitle` helper.
 *
 * NOTE: this covers the WEBVIEW render only — the Kotlin approval gate
 * (MemoryWriteClassifier + AgentLoop forcing per-invocation approval) is covered
 * by JUnit (AgentLoopMemoryApprovalTest, MemoryWriteClassifierTest). The bridge is
 * mocked here, so a real approval round-trip is not exercised.
 */
test.beforeEach(async ({ page }) => {
  await page.goto('/playwright.html');
});

test('memory write approval card shows the memory verb title', async ({ page }) => {
  const host = page.locator('[data-testid="memory-approval-host"]');
  await expect(host.getByText('Updating memory · prefs').first()).toBeVisible();
});

test('memory write approval card omits the Allow-for-session button', async ({ page }) => {
  const host = page.locator('[data-testid="memory-approval-host"]');
  await expect(host.getByRole('button', { name: 'Allow for session' })).toHaveCount(0);
});

test('non-memory edit keeps the default title and the Allow-for-session button', async ({ page }) => {
  const host = page.locator('[data-testid="nonmemory-approval-host"]');
  await expect(host.getByText('Approve edit_file? (LOW risk)').first()).toBeVisible();
  await expect(host.getByRole('button', { name: 'Allow for session' })).toBeVisible();
});
