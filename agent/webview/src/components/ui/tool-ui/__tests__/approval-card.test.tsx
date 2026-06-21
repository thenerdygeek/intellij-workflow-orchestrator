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

describe('ApprovalCard prefix button label variants', () => {
  it('renders the exact prefix in the button label for "git add"', () => {
    render(
      <ApprovalCard
        id="ac-label-1"
        title="Run git add ?"
        commandPrefix="git add"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        onApproveCommandPrefix={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: 'Approve all "git add" this session' })).toBeTruthy();
  });

  it('renders the exact prefix in the button label for "npm install"', () => {
    render(
      <ApprovalCard
        id="ac-label-2"
        title="Run npm install ?"
        commandPrefix="npm install"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        onApproveCommandPrefix={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: 'Approve all "npm install" this session' })).toBeTruthy();
  });
});

describe('ApprovalCard prefix button absent when commandPrefix is empty or undefined', () => {
  it('does NOT render the prefix button when commandPrefix is an empty string', () => {
    render(
      <ApprovalCard
        id="ac-empty-1"
        title="Run x ?"
        commandPrefix=""
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        onApproveCommandPrefix={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: /Approve all .* this session/ })).toBeNull();
  });

  it('does NOT render the prefix button when commandPrefix is undefined', () => {
    render(
      <ApprovalCard
        id="ac-empty-2"
        title="Run y ?"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: /Approve all .* this session/ })).toBeNull();
  });
});

describe('ApprovalCard sibling button regression', () => {
  it('Approve (confirm) button still routes to onConfirm', () => {
    const onConfirm = vi.fn();
    render(
      <ApprovalCard
        id="ac-reg-1"
        title="Run git commit ?"
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('Deny (cancel) button still routes to onCancel', () => {
    const onCancel = vi.fn();
    render(
      <ApprovalCard
        id="ac-reg-2"
        title="Run rm x ?"
        onConfirm={vi.fn()}
        onCancel={onCancel}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Deny' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('"Allow for session" button still routes to onAllowForSession', () => {
    const onAllowForSession = vi.fn();
    render(
      <ApprovalCard
        id="ac-reg-3"
        title="Edit file ?"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        onAllowForSession={onAllowForSession}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Allow for session' }));
    expect(onAllowForSession).toHaveBeenCalledTimes(1);
  });

  it('all four action buttons coexist when both onAllowForSession and commandPrefix are present', () => {
    render(
      <ApprovalCard
        id="ac-reg-4"
        title="Run git add . ?"
        commandPrefix="git add"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        onAllowForSession={vi.fn()}
        onApproveCommandPrefix={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: 'Deny' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Allow for session' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Approve all "git add" this session' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeTruthy();
  });
});
