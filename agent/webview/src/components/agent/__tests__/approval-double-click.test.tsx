/**
 * #3: a double-click on Approve (common on a laggy JCEF webview) must dispatch
 * exactly one decision — not approve twice (or approve then deny). The card
 * latches the first action.
 */
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ApprovalView } from '../ApprovalView';

vi.mock('@/components/rich/DiffHtml', () => ({
  DiffHtml: ({ diffSource }: { diffSource: string }) => <pre data-testid="diff">{diffSource}</pre>,
}));
vi.mock('@/hooks/useShiki', () => ({
  useShiki: () => ({ html: '', isLoading: false }),
}));

beforeEach(() => cleanup());
afterEach(() => vi.clearAllMocks());

describe('ApprovalView double-click', () => {
  it('fires onApprove only once when Approve is clicked twice', () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    render(
      <ApprovalView toolName="edit_file" riskLevel="MEDIUM" title="Approve edit_file?" onApprove={onApprove} onDeny={onDeny} />,
    );
    const approve = screen.getByRole('button', { name: /approve/i });
    fireEvent.click(approve);
    fireEvent.click(approve);
    expect(onApprove).toHaveBeenCalledTimes(1);
    expect(onDeny).not.toHaveBeenCalled();
  });

  it('does not also fire deny if approve then deny are clicked in the same tick', () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    render(
      <ApprovalView toolName="edit_file" riskLevel="MEDIUM" title="Approve edit_file?" onApprove={onApprove} onDeny={onDeny} />,
    );
    fireEvent.click(screen.getByRole('button', { name: /approve/i }));
    fireEvent.click(screen.getByRole('button', { name: /deny/i }));
    expect(onApprove).toHaveBeenCalledTimes(1);
    expect(onDeny).not.toHaveBeenCalled();
  });
});
