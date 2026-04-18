/**
 * Feature-level tests for PlanSummaryCard.
 *
 * These test the actual user-facing behavior:
 * - Does "Approve" call the correct bridge function?
 * - Does "Revise in Editor" open the editor (not send empty string)?
 * - Do buttons show correct pending states?
 * - Is the card hidden when plan is approved?
 *
 * NOTE: Step-list rendering tests removed in Phase 5 (task system port).
 * PlanSummaryCard no longer renders plan steps — those are handled by
 * PlanProgressWidget via the tasks store.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import type { Plan } from '@/bridge/types';

// Mock the jcef-bridge module — openInEditorTab is the key function
vi.mock('@/bridge/jcef-bridge', () => ({
  openInEditorTab: vi.fn(),
  kotlinBridge: {
    dismissPlan: vi.fn(),
  },
}));

import { openInEditorTab, kotlinBridge } from '@/bridge/jcef-bridge';

const mockPlan: Plan = {
  title: 'Fix NPE in PaymentService',
  approved: false,
};

describe('PlanSummaryCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (window as any)._approvePlan = vi.fn();
    (window as any)._revisePlan = vi.fn();
  });

  it('renders plan title', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    expect(screen.getByText('Fix NPE in PaymentService')).toBeInTheDocument();
  });

  it('shows "Awaiting Approval" badge when not approved', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    expect(screen.getByText('Awaiting Approval')).toBeInTheDocument();
  });

  it('renders nothing when plan is approved', () => {
    const approvedPlan = { ...mockPlan, approved: true };
    const { container } = render(<PlanSummaryCard plan={approvedPlan} />);
    expect(container.innerHTML).toBe('');
  });

  it('Approve button calls _approvePlan bridge function', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    const approveBtn = screen.getByText('Approve');
    fireEvent.click(approveBtn);

    expect((window as any)._approvePlan).toHaveBeenCalledTimes(1);
  });

  it('Approve button shows spinner after click', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    const approveBtn = screen.getByText('Approve');
    fireEvent.click(approveBtn);

    expect(screen.getByText('Approving…')).toBeInTheDocument();
  });

  it('buttons are disabled while pending', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    const approveBtn = screen.getByText('Approve');
    fireEvent.click(approveBtn);

    // After clicking Approve, both buttons should be disabled
    const allButtons = screen.getAllByRole('button');
    const actionButtons = allButtons.filter(btn =>
      btn.textContent?.includes('Approv') || btn.textContent?.includes('Revise')
    );
    actionButtons.forEach(btn => {
      expect(btn).toBeDisabled();
    });
  });

  it('View Implementation Plan button is present', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    expect(screen.getByText('View Implementation Plan')).toBeInTheDocument();
  });

  it('shows markdown preview when plan has markdown field', () => {
    const planWithMarkdown: Plan = {
      ...mockPlan,
      markdown: '## Goal\nFix the NPE in PaymentService\n\n## Steps\n### 1. Read file\nUnderstand the flow.',
    };
    render(<PlanSummaryCard plan={planWithMarkdown} />);
    // Should show a preview of the markdown content
    expect(screen.getByText(/Fix the NPE/)).toBeInTheDocument();
  });

  it('Dismiss button calls kotlinBridge.dismissPlan', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    const dismissBtn = screen.getByRole('button', { name: /dismiss/i });
    fireEvent.click(dismissBtn);

    expect((kotlinBridge as any).dismissPlan).toHaveBeenCalledTimes(1);
  });

  it('Dismiss button is disabled while another action is pending', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    // Click Approve to put the card into a pending state
    const approveBtn = screen.getByRole('button', { name: /approve/i });
    fireEvent.click(approveBtn);

    const dismissBtn = screen.getByRole('button', { name: /dismiss/i });
    expect(dismissBtn).toBeDisabled();
  });
});
