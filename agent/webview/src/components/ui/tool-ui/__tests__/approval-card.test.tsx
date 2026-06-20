/**
 * ApprovalCard — prefix-approval button.
 *
 * When the pending approval carries a `commandPrefix` (e.g. "git add"), the card
 * renders an extra "Approve all \"git add\" this session" button that calls
 * `onApproveCommandPrefix(commandPrefix)`. When no prefix is present the button
 * is NOT rendered. Mirrors the existing "Allow for session" affordance.
 */
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ApprovalCard } from '../approval-card';

beforeEach(() => cleanup());
afterEach(() => vi.clearAllMocks());

describe('ApprovalCard prefix-approval button', () => {
  it('renders the prefix button and calls onApproveCommandPrefix with the prefix when clicked', () => {
    const onApproveCommandPrefix = vi.fn();
    render(
      <ApprovalCard
        id="ac-1"
        title="Run git add . ?"
        commandPrefix="git add"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        onApproveCommandPrefix={onApproveCommandPrefix}
      />,
    );
    const btn = screen.getByRole('button', { name: 'Approve all "git add" this session' });
    fireEvent.click(btn);
    expect(onApproveCommandPrefix).toHaveBeenCalledTimes(1);
    expect(onApproveCommandPrefix).toHaveBeenCalledWith('git add');
  });

  it('does NOT render the prefix button when commandPrefix is absent', () => {
    render(
      <ApprovalCard
        id="ac-2"
        title="Run rm -rf x ?"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: /Approve all .* this session/ })).toBeNull();
  });

  it('does not double-fire the prefix action on a double-click (decision latch)', () => {
    const onApproveCommandPrefix = vi.fn();
    render(
      <ApprovalCard
        id="ac-3"
        title="Run git push ?"
        commandPrefix="git push"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        onApproveCommandPrefix={onApproveCommandPrefix}
      />,
    );
    const btn = screen.getByRole('button', { name: 'Approve all "git push" this session' });
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect(onApproveCommandPrefix).toHaveBeenCalledTimes(1);
  });
});
