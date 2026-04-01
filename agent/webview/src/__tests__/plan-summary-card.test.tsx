/**
 * Feature-level tests for PlanSummaryCard.
 *
 * These test the actual user-facing behavior:
 * - Does "Approve" call the correct bridge function?
 * - Does "Revise in Editor" open the editor (not send empty string)?
 * - Do buttons show correct pending states?
 * - Is the card hidden when plan is approved?
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import type { Plan } from '@/bridge/types';

// Mock the jcef-bridge module — openInEditorTab is the key function
vi.mock('@/bridge/jcef-bridge', () => ({
  openInEditorTab: vi.fn(),
}));

import { openInEditorTab } from '@/bridge/jcef-bridge';

const mockPlan: Plan = {
  title: 'Fix NPE in PaymentService',
  goal: 'Add null check for customer reference',
  approach: 'Guard clause at entry point',
  steps: [
    { id: 'step-1', title: 'Read PaymentService.kt', status: 'pending', description: 'Understand the flow' },
    { id: 'step-2', title: 'Add null check', status: 'pending', description: 'Guard clause' },
    { id: 'step-3', title: 'Run tests', status: 'pending', description: 'Verify fix' },
  ],
  testing: 'Run PaymentServiceTest',
  approved: false,
};

describe('PlanSummaryCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (window as any)._approvePlan = vi.fn();
    (window as any)._revisePlan = vi.fn();
  });

  it('renders plan title and step count', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    expect(screen.getByText('Fix NPE in PaymentService')).toBeInTheDocument();
    expect(screen.getByText(/3 steps planned/)).toBeInTheDocument();
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

  // CRITICAL: This was the bug — Revise used to send empty string to _revisePlan
  it('Revise button opens editor tab instead of sending empty string', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    const reviseBtn = screen.getByText('Revise in Editor');
    fireEvent.click(reviseBtn);

    // Should open editor tab, NOT call _revisePlan with empty string
    expect(openInEditorTab).toHaveBeenCalledWith('plan', expect.any(String));
    expect((window as any)._revisePlan).not.toHaveBeenCalled();
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

  it('View Implementation Plan button opens editor tab', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    const viewBtn = screen.getByText('View Implementation Plan');
    fireEvent.click(viewBtn);

    expect(openInEditorTab).toHaveBeenCalledWith('plan', JSON.stringify(mockPlan));
  });

  it('shows markdown preview when plan has markdown field', () => {
    const planWithMarkdown: Plan = {
      ...mockPlan,
      markdown: '## Goal\nFix the NPE in PaymentService\n\n## Steps\n### 1. Read file\nUnderstand the flow.',
    };
    render(<PlanSummaryCard plan={planWithMarkdown} />);
    // Should show a preview of the markdown content, not the structured step list
    expect(screen.getByText(/Fix the NPE/)).toBeInTheDocument();
  });

  it('falls back to step list when no markdown', () => {
    render(<PlanSummaryCard plan={mockPlan} />);
    // Should show structured step list (PlanCompact) — step titles visible
    expect(screen.getByText('Fix NPE in PaymentService')).toBeInTheDocument();
    expect(screen.getByText(/3 steps planned/)).toBeInTheDocument();
  });
});
