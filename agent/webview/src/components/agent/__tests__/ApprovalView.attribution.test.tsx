import { render, screen, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ApprovalView } from '../ApprovalView';

// ApprovalView uses useId() internally (stable in jsdom), and DiffHtml / CommandPreview
// are not exercised here, so we only need minimal mocks for the heavy lazy deps.
vi.mock('@/components/rich/DiffHtml', () => ({
  DiffHtml: ({ diffSource }: { diffSource: string }) => <pre data-testid="diff">{diffSource}</pre>,
}));

vi.mock('@/hooks/useShiki', () => ({
  useShiki: () => ({ html: '', isLoading: false }),
}));

const baseProps = {
  toolName: 'edit_file',
  riskLevel: 'MEDIUM',
  title: 'Approve edit_file? (MEDIUM risk)',
  onApprove: vi.fn(),
  onDeny: vi.fn(),
};

describe('ApprovalView attribution', () => {
  beforeEach(() => {
    cleanup();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('shows the sub-agent label when the approval originates from a sub-agent', () => {
    render(
      <ApprovalView
        {...baseProps}
        originAgentId="a-1"
        originLabel="code-reviewer"
      />
    );
    expect(screen.getByText(/Sub-agent: code-reviewer/)).toBeTruthy();
  });

  it('omits the attribution row when origin is null', () => {
    render(<ApprovalView {...baseProps} />);
    expect(screen.queryByText(/Sub-agent:/)).toBeNull();
  });

  it('omits the attribution row when originLabel is explicitly null', () => {
    render(
      <ApprovalView
        {...baseProps}
        originAgentId="a-2"
        originLabel={null}
      />
    );
    expect(screen.queryByText(/Sub-agent:/)).toBeNull();
  });
});
