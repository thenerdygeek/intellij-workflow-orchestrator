/**
 * Scenario tests for DiffHtml — the component that renders side-by-side diffs
 * using the lazily-loaded diff2html library.
 *
 * Key scenarios tested:
 * 1. Container div is always mounted (even during loading) — the deadlock fix
 * 2. Fallback renders when diff2html fails to load (timeout)
 * 3. Diff content renders correctly when diff2html loads successfully
 * 4. Hunk action buttons appear when callbacks are provided
 * 5. ApprovalView integration — container available in real usage context
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';

// ── Helpers ──

const SAMPLE_DIFF = `--- a/src/main/kotlin/Service.kt
+++ b/src/main/kotlin/Service.kt
@@ -10,7 +10,7 @@
 class Service {
-    fun oldMethod() {
+    fun newMethod() {
         return 42
     }
 }`;

const SAMPLE_DIFF_NO_HEADER = `@@ -1,3 +1,3 @@
-old line
+new line
 context`;

describe('DiffHtml', () => {
  let DiffHtmlMod: typeof import('@/components/rich/DiffHtml');

  beforeEach(async () => {
    vi.resetModules();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // ────────────────────────────────────────────────────────────────────────
  // Group A: Tests with diff2html as a never-resolving promise (loading state)
  // ────────────────────────────────────────────────────────────────────────

  describe('Scenario 1: Container div always mounted during loading (deadlock fix)', () => {
    beforeEach(async () => {
      vi.doMock('diff2html', () => new Promise(() => {})); // never resolves
      vi.doMock('diff2html/bundles/css/diff2html.min.css', () => ({}));
      DiffHtmlMod = await import('@/components/rich/DiffHtml');
    });

    it('renders the container div even while diff2html is loading', () => {
      const { container } = render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);

      // The container div with class "diff-container" must exist in the DOM
      // even during loading — this is the fix for the chicken-and-egg deadlock
      const diffContainer = container.querySelector('.diff-container');
      expect(diffContainer).toBeInTheDocument();
    });

    it('hides the container div while loading (display:none)', () => {
      const { container } = render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);

      const diffContainer = container.querySelector('.diff-container');
      expect(diffContainer).toBeInTheDocument();
      expect(diffContainer).toHaveStyle({ display: 'none' });
    });

    it('shows loading skeleton while diff2html is loading', () => {
      render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);
      expect(screen.getByLabelText('Loading diff')).toBeInTheDocument();
    });

    it('does not show file path header during loading', () => {
      render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);
      // File path header is conditionally hidden during loading
      expect(screen.queryByText('src/main/kotlin/Service.kt')).not.toBeInTheDocument();
    });
  });

  // ────────────────────────────────────────────────────────────────────────
  // Group B: Tests with diff2html rejecting (import error)
  // ────────────────────────────────────────────────────────────────────────

  describe('Scenario 2: Fallback on diff2html rendering failure', () => {
    beforeEach(async () => {
      // Mock diff2html with a html() that throws — simulates corrupted/incompatible module
      vi.doMock('diff2html', () => ({
        html: () => { throw new Error('diff2html rendering failed'); },
      }));
      vi.doMock('diff2html/bundles/css/diff2html.min.css', () => ({}));
      DiffHtmlMod = await import('@/components/rich/DiffHtml');
    });

    it('renders colored raw diff fallback when diff2html.html() throws', async () => {
      render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);

      // Should switch to the raw diff fallback (error early-return path)
      await waitFor(() => {
        expect(screen.queryByLabelText('Loading diff')).not.toBeInTheDocument();
        expect(screen.getByText(/fun oldMethod/)).toBeInTheDocument();
        expect(screen.getByText(/fun newMethod/)).toBeInTheDocument();
      });
    });

    it('shows file path in fallback view', async () => {
      render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);

      await waitFor(() => {
        expect(screen.getByText('src/main/kotlin/Service.kt')).toBeInTheDocument();
      });
    });
  });

  // ────────────────────────────────────────────────────────────────────────
  // Group C: Tests with diff2html resolving successfully
  // ────────────────────────────────────────────────────────────────────────

  describe('Scenario 3: Successful diff2html load', () => {
    const fakeDiff2Html = {
      html: vi.fn().mockReturnValue(
        '<div class="d2h-wrapper"><table><tbody class="d2h-diff-tbody">' +
          '<tr><td class="d2h-code-linenumber">10</td><td class="d2h-code-side-line">rendered diff</td>' +
          '<td class="d2h-code-linenumber">10</td><td class="d2h-code-side-line">rendered diff new</td></tr>' +
          '</tbody></table></div>',
      ),
    };

    beforeEach(async () => {
      vi.doMock('diff2html', () => Promise.resolve(fakeDiff2Html));
      vi.doMock('diff2html/bundles/css/diff2html.min.css', () => ({}));
      fakeDiff2Html.html.mockClear();
      DiffHtmlMod = await import('@/components/rich/DiffHtml');
    });

    it('clears loading state and shows container when diff2html resolves', async () => {
      const { container } = render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);

      await waitFor(() => {
        const diffContainer = container.querySelector('.diff-container');
        expect(diffContainer).toBeInTheDocument();
        // Container should now be visible (no display:none)
        expect(diffContainer).not.toHaveStyle({ display: 'none' });
      });

      // Loading skeleton should be gone
      expect(screen.queryByLabelText('Loading diff')).not.toBeInTheDocument();
    });

    it('calls diff2html.html() with correct options', async () => {
      render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);

      await waitFor(() => {
        expect(fakeDiff2Html.html).toHaveBeenCalledWith(
          SAMPLE_DIFF,
          expect.objectContaining({
            drawFileList: false,
            outputFormat: 'side-by-side',
            matching: 'lines',
            diffStyle: 'word',
          }),
        );
      });
    });

    it('injects diff2html output into the container div', async () => {
      const { container } = render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);

      await waitFor(() => {
        const diffContainer = container.querySelector('.diff-container');
        expect(diffContainer?.innerHTML).toContain('rendered diff');
      });
    });

    it('shows file path header after successful load', async () => {
      render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);

      await waitFor(() => {
        expect(screen.getByText('src/main/kotlin/Service.kt')).toBeInTheDocument();
      });
    });
  });

  // ────────────────────────────────────────────────────────────────────────
  // Group D: Hunk action buttons
  // ────────────────────────────────────────────────────────────────────────

  describe('Scenario 4: Hunk action buttons', () => {
    const fakeDiff2Html = {
      html: vi.fn().mockReturnValue(
        '<div class="d2h-wrapper"><table><tbody class="d2h-diff-tbody">' +
          '<tr><td>hunk content</td></tr>' +
          '</tbody></table></div>',
      ),
    };

    beforeEach(async () => {
      vi.doMock('diff2html', () => Promise.resolve(fakeDiff2Html));
      vi.doMock('diff2html/bundles/css/diff2html.min.css', () => ({}));
      fakeDiff2Html.html.mockClear();
      DiffHtmlMod = await import('@/components/rich/DiffHtml');
    });

    it('renders accept/edit/reject buttons for each hunk when callbacks provided', async () => {
      const onAccept = vi.fn();
      const onReject = vi.fn();

      const { container } = render(
        <DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} onAcceptHunk={onAccept} onRejectHunk={onReject} />,
      );

      await waitFor(() => {
        const buttons = container.querySelectorAll('.diff-hunk-btn');
        expect(buttons.length).toBe(3); // Accept, Edit, Reject
      });

      const acceptBtn = container.querySelector('.diff-hunk-accept');
      expect(acceptBtn?.textContent).toBe('Accept');

      const editBtn = container.querySelector('.diff-hunk-edit');
      expect(editBtn?.textContent).toBe('Edit');

      const rejectBtn = container.querySelector('.diff-hunk-reject');
      expect(rejectBtn?.textContent).toBe('Reject');
    });

    it('does not render action buttons when no callbacks provided', async () => {
      const { container } = render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF} />);

      await waitFor(() => {
        const diffContainer = container.querySelector('.diff-container');
        expect(diffContainer).not.toHaveStyle({ display: 'none' });
      });

      const buttons = container.querySelectorAll('.diff-hunk-btn');
      expect(buttons.length).toBe(0);
    });
  });

  // ────────────────────────────────────────────────────────────────────────
  // Group E: Edge cases and integration
  // ────────────────────────────────────────────────────────────────────────

  describe('Scenario 5: Edge cases', () => {
    beforeEach(async () => {
      vi.doMock('diff2html', () => new Promise(() => {}));
      vi.doMock('diff2html/bundles/css/diff2html.min.css', () => ({}));
      DiffHtmlMod = await import('@/components/rich/DiffHtml');
    });

    it('handles diff without file path header', () => {
      const { container } = render(<DiffHtmlMod.DiffHtml diffSource={SAMPLE_DIFF_NO_HEADER} />);

      // Should render without errors
      const diffContainer = container.querySelector('.diff-container');
      expect(diffContainer).toBeInTheDocument();
    });

    it('handles empty diff source', () => {
      const { container } = render(<DiffHtmlMod.DiffHtml diffSource="" />);

      const diffContainer = container.querySelector('.diff-container');
      expect(diffContainer).toBeInTheDocument();
    });
  });

  describe('Scenario 6: ApprovalView integration', () => {
    beforeEach(async () => {
      vi.doMock('diff2html', () => new Promise(() => {}));
      vi.doMock('diff2html/bundles/css/diff2html.min.css', () => ({}));
    });

    it('DiffHtml inside ApprovalView has container mounted during loading', async () => {
      const { ApprovalView } = await import('@/components/agent/ApprovalView');

      const { container } = render(
        <ApprovalView
          toolName="edit_file"
          riskLevel="MEDIUM"
          title="Edit Service.kt"
          description="Rename method"
          diffContent={SAMPLE_DIFF}
          onApprove={vi.fn()}
          onDeny={vi.fn()}
          onAllowForSession={vi.fn()}
        />,
      );

      // The diff-container must be in the DOM even while loading
      // This is the exact scenario that was broken before the fix
      const diffContainer = container.querySelector('.diff-container');
      expect(diffContainer).toBeInTheDocument();
    });

    it('ApprovalView without diffContent does not render DiffHtml', async () => {
      const { ApprovalView } = await import('@/components/agent/ApprovalView');

      const { container } = render(
        <ApprovalView
          toolName="run_command"
          riskLevel="HIGH"
          title="Run dangerous command"
          onApprove={vi.fn()}
          onDeny={vi.fn()}
          onAllowForSession={vi.fn()}
        />,
      );

      // No diff container when no diffContent
      const diffContainer = container.querySelector('.diff-container');
      expect(diffContainer).not.toBeInTheDocument();
    });
  });
});
