import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { ApprovalView } from '@/components/agent/ApprovalView';

vi.mock('@/hooks/useShiki', () => ({
  useShiki: (code: string) => ({
    html: `<pre class="shiki"><code>${code}</code></pre>`,
    isLoading: false,
  }),
}));

// DiffHtml lazy-imports diff2html which is flaky in jsdom — stub it so the
// routing test doesn't care about diff rendering, only that DiffHtml mounts
// (asserted via the data-testid we add in ApprovalView).
vi.mock('@/components/rich/DiffHtml', () => ({
  DiffHtml: () => <div data-testid="diff-html-stub" />,
  preloadDiff2Html: () => undefined,
}));

describe('ApprovalView tool routing', () => {
  beforeEach(() => {
    cleanup();
    Object.defineProperty(window, 'location', {
      value: { ...window.location, origin: 'http://workflow-agent' },
      writable: true,
      configurable: true,
    });
    (window as any)._copyToClipboard = vi.fn();
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', {
      value: { ...window.location, origin: 'http://localhost' },
      writable: true,
      configurable: true,
    });
  });

  it('run_command routes to CommandPreview and skips DiffHtml', () => {
    render(
      <ApprovalView
        toolName="run_command"
        riskLevel="MEDIUM"
        title="Run: npm test"
        commandPreview={{
          command: 'npm test',
          shell: '/bin/bash',
          cwd: '/x',
          env: [],
        }}
        onApprove={() => {}}
        onDeny={() => {}}
      />
    );
    expect(screen.getByTestId('command-preview')).toBeInTheDocument();
    expect(screen.queryByTestId('approval-diff')).not.toBeInTheDocument();
    expect(screen.queryByTestId('diff-html-stub')).not.toBeInTheDocument();
  });

  it('edit_file routes to DiffHtml and skips CommandPreview', () => {
    render(
      <ApprovalView
        toolName="edit_file"
        riskLevel="LOW"
        title="Edit x.kt"
        diffContent={'--- a\n+++ b\n@@ -1 +1 @@\n-a\n+b\n'}
        onApprove={() => {}}
        onDeny={() => {}}
      />
    );
    expect(screen.getByTestId('approval-diff')).toBeInTheDocument();
    expect(screen.queryByTestId('command-preview')).not.toBeInTheDocument();
  });

  it('run_command with only diffContent (legacy fallback) still does not render DiffHtml', () => {
    render(
      <ApprovalView
        toolName="run_command"
        riskLevel="MEDIUM"
        title="Run: ls"
        diffContent={'$ ls\n(shell: /bin/bash)'}
        onApprove={() => {}}
        onDeny={() => {}}
      />
    );
    expect(screen.queryByTestId('approval-diff')).not.toBeInTheDocument();
  });
});
