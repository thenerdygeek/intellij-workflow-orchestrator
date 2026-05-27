/**
 * Interaction coverage for the remaining PlanSummaryCard actions: View Plan and
 * the Revise path (shown when the plan has review comments). Approve / Dismiss /
 * pending / hidden-when-approved are covered by plan-summary-card.test.tsx.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import { useChatStore } from '@/stores/chatStore';
import type { Plan } from '@/bridge/types';

vi.mock('@/bridge/jcef-bridge', () => ({
  openInEditorTab: vi.fn(),
  kotlinBridge: { dismissPlan: vi.fn() },
}));

const plan: Plan = { title: 'Refactor auth', approved: false, revision: 1 };

beforeEach(() => {
  vi.clearAllMocks();
  (window as any)._approvePlan = vi.fn();
  (window as any)._focusPlanEditor = vi.fn();
  (window as any)._revisePlanFromEditor = vi.fn();
  act(() => useChatStore.getState().setPlanCommentCount(0));
});

describe('PlanSummaryCard — View Plan & Revise', () => {
  it('"View Implementation Plan" focuses the plan editor', () => {
    render(<PlanSummaryCard plan={plan} />);
    fireEvent.click(screen.getByRole('button', { name: /view implementation plan/i }));
    expect((window as any)._focusPlanEditor).toHaveBeenCalledTimes(1);
  });

  it('shows "Revise (N)" instead of Approve when there are comments', () => {
    act(() => useChatStore.getState().setPlanCommentCount(2));
    render(<PlanSummaryCard plan={plan} />);

    expect(screen.queryByRole('button', { name: /^approve$/i })).toBeNull();
    const revise = screen.getByRole('button', { name: /revise/i });
    expect(revise).toHaveTextContent('2');
  });

  it('clicking Revise triggers the editor revise bridge and shows a pending state', () => {
    act(() => useChatStore.getState().setPlanCommentCount(1));
    render(<PlanSummaryCard plan={plan} />);

    fireEvent.click(screen.getByRole('button', { name: /revise/i }));
    expect((window as any)._revisePlanFromEditor).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Revising…')).toBeInTheDocument();
  });

  it('shows the comment-count badge instead of "Awaiting Approval"', () => {
    act(() => useChatStore.getState().setPlanCommentCount(3));
    render(<PlanSummaryCard plan={plan} />);
    expect(screen.queryByText('Awaiting Approval')).toBeNull();
    expect(screen.getByText(/3 comments/)).toBeInTheDocument();
  });
});
